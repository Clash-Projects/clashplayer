package com.clashplayer.automix

import kotlin.math.*

/**
 * Rhythm and level facts about one window of a track for DJ AutoMix.
 */
data class TrackAnalysis(
    val windowStart: Double,
    val windowEnd: Double,
    val bpm: Double,
    val confidence: Double,
    val beats: List<Double>,
    val downbeats: List<Double>,
    val audibleStart: Double,
    val contentEnd: Double,
    val energyDb: List<Double>,
    val energyHop: Double = 0.5
) {
    val beatPeriod: Double get() = if (bpm > 0) 60.0 / bpm else 0.0
    val hasBeat: Boolean get() = bpm > 0 && confidence >= 0.15 && beats.size >= 8
}

object AudioAnalyzer {
    private const val FFT_SIZE = 512
    private const val HOP = 128

    fun analyzePcm(pcm: ShortArray, sampleRate: Int, offsetSeconds: Double = 0.0): TrackAnalysis {
        val windowEnd = offsetSeconds + pcm.size.toDouble() / sampleRate
        val fps = sampleRate.toDouble() / HOP
        val nFrames = if (pcm.size < FFT_SIZE) 0 else 1 + (pcm.size - FFT_SIZE) / HOP

        // 1. Levels & Audible Bounds
        val energyHop = 0.5
        val eLen = max(1, ceil(pcm.size.toDouble() / (sampleRate * energyHop)).toInt())
        val energyDb = MutableList(eLen) { -120.0 }
        val eWin = (sampleRate * energyHop).roundToInt()
        for (i in 0 until eLen) {
            val s = i * eWin
            val e = min(pcm.size, s + eWin)
            if (e <= s) break
            var acc = 0.0
            for (k in s until e) {
                val v = pcm[k] / 32768.0
                acc += v * v
            }
            val rms = sqrt(acc / (e - s))
            energyDb[i] = if (rms <= 1e-6) -120.0 else 20.0 * log10(rms)
        }

        val (audibleStart, contentEnd) = computeAudibleBounds(pcm, sampleRate, offsetSeconds)

        if (nFrames < fps * 8) {
            return TrackAnalysis(
                windowStart = offsetSeconds,
                windowEnd = windowEnd,
                bpm = 0.0,
                confidence = 0.0,
                beats = emptyList(),
                downbeats = emptyList(),
                audibleStart = audibleStart,
                contentEnd = contentEnd,
                energyDb = energyDb,
                energyHop = energyHop
            )
        }

        // 2. Onset Envelope (Spectral Flux)
        val window = DoubleArray(FFT_SIZE) { i ->
            0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))
        }
        val half = FFT_SIZE / 2
        val lowBins = max(2, (150.0 / (sampleRate.toDouble() / FFT_SIZE)).toInt())
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        var prev = DoubleArray(half)
        var cur = DoubleArray(half)
        val flux = DoubleArray(nFrames)
        val lowFlux = DoubleArray(nFrames)

        for (f in 0 until nFrames) {
            val base = f * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = (pcm[base + i] / 32768.0) * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            var sum = 0.0
            var low = 0.0
            for (b in 1 until half) {
                val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                val v = ln(1.0 + 100.0 * mag)
                cur[b] = v
                val d = v - prev[b]
                if (d > 0) {
                    sum += d
                    if (b <= lowBins) low += d
                }
            }
            flux[f] = if (f == 0) 0.0 else sum
            lowFlux[f] = if (f == 0) 0.0 else low
            val t = prev
            prev = cur
            cur = t
        }

        val fullOnset = detrend(flux, (fps * 0.4).roundToInt())
        val lowOnset = detrend(lowFlux, (fps * 0.4).roundToInt())

        val onset = DoubleArray(fullOnset.size)
        var mf = 0.0
        var ml = 0.0
        for (i in fullOnset.indices) {
            mf += fullOnset[i]
            ml += lowOnset[i]
        }
        mf = if (mf <= 1e-12) 1.0 else mf / fullOnset.size
        ml = if (ml <= 1e-12) 0.0 else ml / lowOnset.size
        for (i in onset.indices) {
            onset[i] = fullOnset[i] / mf + (if (ml > 0) 2.5 * lowOnset[i] / ml else 0.0)
        }

        // 3. Tempo via Autocorrelation
        val minLag = (fps * 60.0 / 200.0).toInt()
        val maxLag = min(onset.size / 3, ceil(fps * 60.0 / 60.0).toInt())
        if (maxLag <= minLag + 2) {
            return noBeat(offsetSeconds, windowEnd, audibleStart, contentEnd, energyDb, energyHop)
        }

        val acf = DoubleArray(max(maxLag * 4 + 2, onset.size / 2))
        val acfLen = min(acf.size, onset.size - 1)
        var zero = 0.0
        for (t in onset.indices) zero += onset[t] * onset[t]
        if (zero <= 1e-12) {
            return noBeat(offsetSeconds, windowEnd, audibleStart, contentEnd, energyDb, energyHop)
        }

        for (lag in 1 until acfLen) {
            var s = 0.0
            for (t in 0 until (onset.size - lag)) {
                s += onset[t] * onset[t + lag]
            }
            acf[lag] = s / zero
        }

        fun acfAt(lag: Double): Double {
            val i = lag.toInt()
            if (i < 1 || i + 1 >= acfLen) return 0.0
            val fr = lag - i
            return acf[i] * (1.0 - fr) + acf[i + 1] * fr
        }

        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * fps / lag
            val lg = log2(bpm / 120.0)
            val prior = exp(-0.5 * (lg / 0.8) * (lg / 0.8))
            scores[lag] = (acf[lag] +
                0.5 * acfAt(lag * 2.0) +
                0.33 * acfAt(lag * 3.0) +
                0.25 * acfAt(lag * 4.0)) * (0.5 + prior)
        }

        val peaks = mutableListOf<Int>()
        for (lag in (minLag + 1) until maxLag) {
            if (scores[lag] >= scores[lag - 1] && scores[lag] > scores[lag + 1]) {
                peaks.add(lag)
            }
        }
        peaks.sortByDescending { scores[it] }

        val candidates = mutableListOf<Int>()
        for (l in peaks) {
            if (candidates.any { abs(it - l) <= it * 0.04 }) continue
            if (scores[l] < scores[peaks.first()] * 0.6) break
            candidates.add(l)
            if (candidates.size == 3) break
        }
        if (candidates.isEmpty()) {
            return noBeat(offsetSeconds, windowEnd, audibleStart, contentEnd, energyDb, energyHop)
        }

        var bestGrid: Grid? = null
        for (lag in candidates) {
            val g = fitGrid(onset, acf, acfLen, lag) ?: continue
            val rank = g.stability + 0.1 * scores[lag] / scores[candidates.first()]
            if (bestGrid == null || rank > bestGrid.rank) {
                g.rank = rank
                bestGrid = g
            }
        }
        if (bestGrid == null) {
            return noBeat(offsetSeconds, windowEnd, audibleStart, contentEnd, energyDb, energyHop)
        }

        val period = bestGrid.period
        val phase = bestGrid.phase
        val confidence = (bestGrid.stability * ((bestGrid.contrast - 1.5) / 1.5).coerceIn(0.0, 1.0))

        // 4. Grid & Downbeats
        val beats = mutableListOf<Double>()
        val beatFrames = mutableListOf<Double>()
        var p = phase
        while (p < onset.size) {
            beatFrames.add(p)
            beats.add(offsetSeconds + (p * HOP + FFT_SIZE / 2.0) / sampleRate)
            p += period
        }

        val barScore = DoubleArray(4)
        for (i in beatFrames.indices) {
            barScore[i % 4] += sampleAt(lowOnset, beatFrames[i]) + 0.1 * sampleAt(fullOnset, beatFrames[i])
        }
        var bar = 0
        for (i in 1 until 4) {
            if (barScore[i] > barScore[bar]) bar = i
        }
        val downbeats = mutableListOf<Double>()
        for (i in bar until beats.size step 4) {
            downbeats.add(beats[i])
        }

        return TrackAnalysis(
            windowStart = offsetSeconds,
            windowEnd = windowEnd,
            bpm = 60.0 * fps / period,
            confidence = confidence,
            beats = beats,
            downbeats = downbeats,
            audibleStart = audibleStart,
            contentEnd = contentEnd,
            energyDb = energyDb,
            energyHop = energyHop
        )
    }

    private data class Grid(
        val period: Double,
        val phase: Double,
        val stability: Double,
        val contrast: Double,
        var rank: Double = 0.0
    )

    private fun fitGrid(onset: DoubleArray, acf: DoubleArray, acfLen: Int, lag: Int): Grid? {
        if (lag <= 0 || onset.isEmpty()) return null
        val period = lag.toDouble()
        // Find best phase by comb correlation
        var bestPhase = 0
        var maxEnergy = -1.0
        for (ph in 0 until lag) {
            var sum = 0.0
            var count = 0
            var idx = ph
            while (idx < onset.size) {
                sum += onset[idx]
                count++
                idx += lag
            }
            if (count > 0 && sum > maxEnergy) {
                maxEnergy = sum
                bestPhase = ph
            }
        }
        val meanOnset = onset.average()
        val contrast = if (meanOnset > 1e-9) (maxEnergy / max(1, onset.size / lag)) / meanOnset else 1.0
        val stability = (contrast / 5.0).coerceIn(0.1, 1.0)
        return Grid(period, bestPhase.toDouble(), stability, contrast)
    }

    private fun sampleAt(x: DoubleArray, pos: Double): Double {
        val i = pos.toInt()
        if (i < 0 || i + 1 >= x.size) return 0.0
        val fr = pos - i
        return x[i] * (1.0 - fr) + x[i + 1] * fr
    }

    private fun detrend(x: DoubleArray, win: Int): DoubleArray {
        val out = DoubleArray(x.size)
        val w = max(1, win)
        var sum = 0.0
        val q = DoubleArray(w)
        var head = 0
        for (i in x.indices) {
            sum -= q[head]
            q[head] = x[i]
            sum += x[i]
            head = (head + 1) % w
            val movingAvg = sum / min(i + 1, w)
            out[i] = max(0.0, x[i] - movingAvg)
        }
        return out
    }

    private fun computeAudibleBounds(pcm: ShortArray, sampleRate: Int, offsetSeconds: Double): Pair<Double, Double> {
        val threshold = 327 // approx -40 dBFS
        var first = 0
        while (first < pcm.size && abs(pcm[first].toInt()) < threshold) first++
        var last = pcm.size - 1
        while (last > 0 && abs(pcm[last].toInt()) < threshold) last--
        val audibleStart = offsetSeconds + first.toDouble() / sampleRate
        val contentEnd = offsetSeconds + max(first, last).toDouble() / sampleRate
        return Pair(audibleStart, contentEnd)
    }

    private fun noBeat(
        ws: Double, we: Double, asSec: Double, ceSec: Double,
        energy: List<Double>, hop: Double
    ) = TrackAnalysis(ws, we, 0.0, 0.0, emptyList(), emptyList(), asSec, ceSec, energy, hop)

    // Cooley-Tukey Radix-2 FFT
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
        var l = 2
        while (l <= n) {
            val halfL = l shr 1
            val theta = -2.0 * Math.PI / l
            val wRe = cos(theta)
            val wIm = sin(theta)
            var i = 0
            while (i < n) {
                var uRe = 1.0
                var uIm = 0.0
                for (m in 0 until halfL) {
                    val idx = i + m
                    val match = idx + halfL
                    val vRe = re[match] * uRe - im[match] * uIm
                    val vIm = re[match] * uIm + im[match] * uRe
                    re[match] = re[idx] - vRe
                    im[match] = im[idx] - vIm
                    re[idx] += vRe
                    im[idx] += vIm
                    val nextURe = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe
                    uRe = nextURe
                }
                i += l
            }
            l = l shl 1
        }
    }
}
