package com.clashplayer.dsp.model

import kotlin.math.roundToInt

object AutoEqParser {

    fun parseParametric(name: String, content: String): EqualizerPreset {
        val bands = mutableListOf<EqualizerBand>()
        var preamp = 0.0f

        val preampRegex = Regex("""Preamp:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val filterRegex = Regex(
            """Filter\s+\d+:\s+(ON|OFF)\s+([A-Z]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )

        for (line in content.lines()) {
            val trimmed = line.trim()
            val preampMatch = preampRegex.find(trimmed)
            if (preampMatch != null) {
                preamp = preampMatch.groupValues[1].toFloatOrNull() ?: 0.0f
                continue
            }

            val match = filterRegex.find(trimmed)
            if (match != null) {
                val enabled = match.groupValues[1].equals("ON", ignoreCase = true)
                val typeStr = match.groupValues[2].uppercase()
                val fc = match.groupValues[3].toFloatOrNull()?.roundToInt() ?: 1000
                val gain = match.groupValues[4].toFloatOrNull() ?: 0.0f
                val q = match.groupValues[5].toFloatOrNull() ?: 1.414f

                val filterType = when (typeStr) {
                    "PK" -> FilterType.PEAKING
                    "LSC", "LOW_SHELF", "LS" -> FilterType.LOW_SHELF
                    "HSC", "HIGH_SHELF", "HS" -> FilterType.HIGH_SHELF
                    "LP", "LOW_PASS" -> FilterType.LOW_PASS
                    "HP", "HIGH_PASS" -> FilterType.HIGH_PASS
                    "BP", "BAND_PASS" -> FilterType.BAND_PASS
                    "NO", "NOTCH" -> FilterType.NOTCH
                    else -> FilterType.PEAKING
                }

                val band = EqualizerBand(
                    type = filterType.id,
                    frequency = fc,
                    q = q,
                    gain = (gain / 15.0f).coerceIn(-1.0f, 1.0f),
                    name = "$fc Hz"
                )
                band.enabled = enabled
                bands.add(band)
            }
        }

        return EqualizerPreset(
            name = name,
            type = EqualizerPreset.TYPE_AUTO_EQ,
            isParametric = true,
            parametricBands = bands,
            preamp = preamp
        )
    }
}
