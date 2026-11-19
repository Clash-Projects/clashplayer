#include "ParametricEq.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>

namespace clashplayer::audio {
namespace {

constexpr double kPi = 3.1415926535897932384626433832795;

// Process-wide published configuration. Written under the mutex by the
// control thread; read lock-free by audio threads, which re-read when the
// revision moves (a torn read is corrected by the next revision bump).
struct SharedConfig final {
    std::atomic<std::uint32_t> revision{0};
    std::atomic<bool> enabled{false};
    std::atomic<float> preampDb{0.0F};
    std::atomic<std::int32_t> count{0};
    std::array<std::atomic<std::int32_t>, ParametricEq::kMaxFilters> types{};
    std::array<std::atomic<float>, ParametricEq::kMaxFilters> frequencies{};
    std::array<std::atomic<float>, ParametricEq::kMaxFilters> gains{};
    std::array<std::atomic<float>, ParametricEq::kMaxFilters> qs{};
    std::array<std::atomic<bool>, ParametricEq::kMaxFilters> filterOn{};
};

SharedConfig& shared() {
    static SharedConfig config;
    return config;
}

std::mutex& publishMutex() {
    static std::mutex mutex;
    return mutex;
}

double clampFrequency(double sampleRate, double frequency) noexcept {
    return std::clamp(frequency, 10.0, sampleRate * 0.49);
}

float sanitize(float value, float low, float high, float fallback) noexcept {
    if (!std::isfinite(value)) return fallback;
    return std::clamp(value, low, high);
}

inline std::int32_t readInt24(const std::uint8_t* p) noexcept {
    std::int32_t value = static_cast<std::int32_t>(p[0]) |
        (static_cast<std::int32_t>(p[1]) << 8) |
        (static_cast<std::int32_t>(static_cast<std::int8_t>(p[2])) << 16);
    return value;
}

inline void writeInt24(std::uint8_t* p, std::int32_t value) noexcept {
    p[0] = static_cast<std::uint8_t>(value & 0xFF);
    p[1] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
    p[2] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
}

}  // namespace

void ParametricEq::publish(
    bool enabled,
    float preampDb,
    const std::int32_t* types,
    const float* frequenciesHz,
    const float* gainsDb,
    const float* qs,
    const std::uint8_t* filterEnabled,
    std::size_t count) noexcept {
    std::lock_guard<std::mutex> lock(publishMutex());
    auto& config = shared();
    const std::size_t safeCount = (types == nullptr || frequenciesHz == nullptr ||
        gainsDb == nullptr || qs == nullptr) ? 0U : std::min(count, kMaxFilters);
    for (std::size_t i = 0; i < safeCount; ++i) {
        const std::int32_t type = (types[i] >= kPeaking && types[i] <= kHighPass) ? types[i] : kPeaking;
        config.types[i].store(type, std::memory_order_relaxed);
        config.frequencies[i].store(sanitize(frequenciesHz[i], 10.0F, 40000.0F, 1000.0F), std::memory_order_relaxed);
        config.gains[i].store(sanitize(gainsDb[i], -30.0F, 30.0F, 0.0F), std::memory_order_relaxed);
        config.qs[i].store(sanitize(qs[i], 0.05F, 30.0F, 0.707F), std::memory_order_relaxed);
        config.filterOn[i].store(filterEnabled == nullptr || filterEnabled[i] != 0U, std::memory_order_relaxed);
    }
    config.count.store(static_cast<std::int32_t>(safeCount), std::memory_order_relaxed);
    config.preampDb.store(sanitize(preampDb, -40.0F, 20.0F, 0.0F), std::memory_order_relaxed);
    config.enabled.store(enabled, std::memory_order_relaxed);
    config.revision.fetch_add(1, std::memory_order_release);
}

bool ParametricEq::isEngaged() noexcept {
    auto& config = shared();
    (void) config.revision.load(std::memory_order_acquire);
    if (!config.enabled.load(std::memory_order_relaxed)) return false;
    if (std::abs(config.preampDb.load(std::memory_order_relaxed)) >= 0.001F) return true;
    const auto count = static_cast<std::size_t>(config.count.load(std::memory_order_relaxed));
    for (std::size_t i = 0; i < count && i < kMaxFilters; ++i) {
        if (!config.filterOn[i].load(std::memory_order_relaxed)) continue;
        const auto type = config.types[i].load(std::memory_order_relaxed);
        if (type == kLowPass || type == kHighPass) return true;
        if (std::abs(config.gains[i].load(std::memory_order_relaxed)) >= 0.001F) return true;
    }
    return false;
}

void ParametricEq::configure(double sampleRate, std::int32_t channels) noexcept {
    sampleRate_ = std::max(sampleRate, 8000.0);
    channels_ = std::clamp<std::int32_t>(channels, 1, static_cast<std::int32_t>(kMaxChannels));
    appliedRevision_ = 0xFFFFFFFFU;
    reset();
    syncConfiguration();
}

void ParametricEq::reset() noexcept {
    for (auto& state : z1_) state.fill(0.0);
    for (auto& state : z2_) state.fill(0.0);
    for (auto& s : simdState_) {
        s.z1[0] = 0.0f; s.z1[1] = 0.0f;
        s.z2[0] = 0.0f; s.z2[1] = 0.0f;
    }
}

void ParametricEq::syncConfiguration() noexcept {
    auto& config = shared();
    const std::uint32_t revision = config.revision.load(std::memory_order_acquire);
    if (revision == appliedRevision_ && appliedSampleRate_ == sampleRate_) return;
    appliedRevision_ = revision;
    appliedSampleRate_ = sampleRate_;
    enabled_ = config.enabled.load(std::memory_order_relaxed);
    const float preampDb = config.preampDb.load(std::memory_order_relaxed);
    preampGain_ = std::pow(10.0, static_cast<double>(preampDb) / 20.0);
    const auto count = std::min(
        static_cast<std::size_t>(std::max(0, config.count.load(std::memory_order_relaxed))),
        kMaxFilters);
    std::size_t active = 0;
    for (std::size_t i = 0; i < count; ++i) {
        if (!config.filterOn[i].load(std::memory_order_relaxed)) continue;
        Spec spec;
        spec.type = config.types[i].load(std::memory_order_relaxed);
        spec.frequency = config.frequencies[i].load(std::memory_order_relaxed);
        spec.gainDb = config.gains[i].load(std::memory_order_relaxed);
        spec.q = config.qs[i].load(std::memory_order_relaxed);
        const bool passFilter = spec.type == kLowPass || spec.type == kHighPass;
        if (!passFilter && std::abs(spec.gainDb) < 0.001) continue;
        // A filter above Nyquist at this rate would alias: skip it.
        if (spec.frequency >= sampleRate_ * 0.49) continue;
        const Coefficients next = design(spec, sampleRate_);
        // Slots are compacted; keep a slot's state only while it still runs
        // the same configured filter, so editing one gain never resets the
        // others (no click), while a toggled filter starts from silence.
        if (slotSource_[active] != static_cast<std::int32_t>(i)) {
            z1_[active].fill(0.0);
            z2_[active].fill(0.0);
            simdState_[active].z1[0] = 0.0f; simdState_[active].z1[1] = 0.0f;
            simdState_[active].z2[0] = 0.0f; simdState_[active].z2[1] = 0.0f;
            slotSource_[active] = static_cast<std::int32_t>(i);
        }
        coefficients_[active] = next;
        simdCoefficients_[active].b0 = static_cast<float>(next.b0);
        simdCoefficients_[active].b1 = static_cast<float>(next.b1);
        simdCoefficients_[active].b2 = static_cast<float>(next.b2);
        simdCoefficients_[active].a1 = static_cast<float>(next.a1);
        simdCoefficients_[active].a2 = static_cast<float>(next.a2);
        ++active;
    }
    for (std::size_t i = active; i < kMaxFilters; ++i) {
        z1_[i].fill(0.0);
        z2_[i].fill(0.0);
        simdState_[i].z1[0] = 0.0f; simdState_[i].z1[1] = 0.0f;
        simdState_[i].z2[0] = 0.0f; simdState_[i].z2[1] = 0.0f;
        slotSource_[i] = -1;
    }
    activeCount_ = active;
    if (!enabled_) activeCount_ = 0;
}

void ParametricEq::processStereoNeon(float* samples, std::size_t frames) noexcept {
    for (std::size_t i = 0; i < activeCount_; ++i) {
        const float b0 = simdCoefficients_[i].b0;
        const float b1 = simdCoefficients_[i].b1;
        const float b2 = simdCoefficients_[i].b2;
        const float a1 = simdCoefficients_[i].a1;
        const float a2 = simdCoefficients_[i].a2;
        SimdState& state = simdState_[i];

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        float32x4_t Z = vld1q_f32(reinterpret_cast<const float*>(&state));
        const float32x4_t B12 = {b1, b1, b2, b2};
        const float32x4_t A12 = {a1, a1, a2, a2};
        const float32x2_t vzero = vdup_n_f32(0.0f);

        std::size_t f = 0;
        for (; f + 1 < frames; f += 2) {
            float* ptr = samples + f * 2;
            float32x4_t x4 = vld1q_f32(ptr);
            float32x2_t x0 = vget_low_f32(x4);
            float32x2_t x1 = vget_high_f32(x4);

            float32x2_t y0 = vmla_n_f32(vget_low_f32(Z), x0, b0);
            float32x4_t X0 = vcombine_f32(x0, x0);
            float32x4_t Y0 = vcombine_f32(y0, y0);
            float32x4_t Z_shift0 = vcombine_f32(vget_high_f32(Z), vzero);
            Z = vmlaq_f32(Z_shift0, B12, X0);
            Z = vmlsq_f32(Z, A12, Y0);

            float32x2_t y1 = vmla_n_f32(vget_low_f32(Z), x1, b0);
            float32x4_t X1 = vcombine_f32(x1, x1);
            float32x4_t Y1 = vcombine_f32(y1, y1);
            float32x4_t Z_shift1 = vcombine_f32(vget_high_f32(Z), vzero);
            Z = vmlaq_f32(Z_shift1, B12, X1);
            Z = vmlsq_f32(Z, A12, Y1);

            vst1q_f32(ptr, vcombine_f32(y0, y1));
        }
        for (; f < frames; ++f) {
            float* ptr = samples + f * 2;
            float32x2_t x0 = vld1_f32(ptr);
            float32x2_t y0 = vmla_n_f32(vget_low_f32(Z), x0, b0);
            vst1_f32(ptr, y0);
            float32x4_t X0 = vcombine_f32(x0, x0);
            float32x4_t Y0 = vcombine_f32(y0, y0);
            float32x4_t Z_shift0 = vcombine_f32(vget_high_f32(Z), vzero);
            Z = vmlaq_f32(Z_shift0, B12, X0);
            Z = vmlsq_f32(Z, A12, Y0);
        }
        vst1q_f32(reinterpret_cast<float*>(&state), Z);
#else
        float z1_L = state.z1[0], z1_R = state.z1[1];
        float z2_L = state.z2[0], z2_R = state.z2[1];
        for (std::size_t f = 0; f < frames; ++f) {
            float* ptr = samples + f * 2;
            const float inL = ptr[0], inR = ptr[1];
            const float outL = b0 * inL + z1_L;
            const float outR = b0 * inR + z1_R;
            z1_L = b1 * inL - a1 * outL + z2_L;
            z1_R = b1 * inR - a1 * outR + z2_R;
            z2_L = b2 * inL - a2 * outL;
            z2_R = b2 * inR - a2 * outR;
            ptr[0] = outL;
            ptr[1] = outR;
        }
        state.z1[0] = z1_L; state.z1[1] = z1_R;
        state.z2[0] = z2_L; state.z2[1] = z2_R;
#endif
    }

    const float gain = static_cast<float>(preampGain_);
    if (std::abs(gain - 1.0f) >= 1.0e-5f) {
        const std::size_t totalSamples = frames * 2;
        std::size_t i = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        const float32x4_t vgain = vdupq_n_f32(gain);
        for (; i + 3 < totalSamples; i += 4) {
            float32x4_t s = vld1q_f32(samples + i);
            s = vmulq_f32(s, vgain);
            vst1q_f32(samples + i, s);
        }
#endif
        for (; i < totalSamples; ++i) {
            samples[i] *= gain;
        }
    }

    for (std::size_t i = 0; i < activeCount_; ++i) {
        for (int ch = 0; ch < 2; ++ch) {
            if (!std::isfinite(simdState_[i].z1[ch]) || std::abs(simdState_[i].z1[ch]) < 1.0e-20f) {
                simdState_[i].z1[ch] = 0.0f;
            }
            if (!std::isfinite(simdState_[i].z2[ch]) || std::abs(simdState_[i].z2[ch]) < 1.0e-20f) {
                simdState_[i].z2[ch] = 0.0f;
            }
        }
    }
}

bool ParametricEq::process(float* samples, std::int32_t frameCount, std::int32_t channelCount) noexcept {
    if (samples == nullptr || frameCount <= 0 || channelCount < 1 ||
        channelCount > static_cast<std::int32_t>(kMaxChannels)) {
        return false;
    }
    syncConfiguration();
    if (!enabled_ || (activeCount_ == 0 && std::abs(preampGain_ - 1.0) < 1.0e-6)) return false;

    if (channelCount == 2) {
        processStereoNeon(samples, static_cast<std::size_t>(frameCount));
        return true;
    }

    const auto channels = static_cast<std::size_t>(channelCount);
    const auto frames = static_cast<std::size_t>(frameCount);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        float* sample = samples + frame * channels;
        for (std::size_t ch = 0; ch < channels; ++ch) {
            const float out = tick(sample[ch], ch);
            sample[ch] = std::isfinite(out) ? out : 0.0F;
        }
    }
    // Denormal / blow-up guard once per block instead of per sample.
    for (std::size_t i = 0; i < activeCount_; ++i) {
        for (std::size_t ch = 0; ch < channels; ++ch) {
            if (!std::isfinite(simdState_[i].z1[ch]) || !std::isfinite(simdState_[i].z2[ch])) {
                simdState_[i].z1[ch] = 0.0f;
                simdState_[i].z2[ch] = 0.0f;
            } else {
                if (std::abs(simdState_[i].z1[ch]) < 1.0e-20f) simdState_[i].z1[ch] = 0.0f;
                if (std::abs(simdState_[i].z2[ch]) < 1.0e-20f) simdState_[i].z2[ch] = 0.0f;
            }
        }
    }
    return true;
}

bool ParametricEq::processPcm(std::uint8_t* data, std::size_t byteCount, std::int32_t encoding) noexcept {
    if (data == nullptr || byteCount == 0) return false;
    syncConfiguration();
    if (!enabled_ || (activeCount_ == 0 && std::abs(preampGain_ - 1.0) < 1.0e-6)) return false;
    const auto channels = static_cast<std::size_t>(channels_);
    std::size_t bytesPerSample = 0;
    switch (encoding) {
        case kPcm16: bytesPerSample = 2; break;
        case kPcm24Packed: bytesPerSample = 3; break;
        case kPcm32: bytesPerSample = 4; break;
        case kPcmFloat: bytesPerSample = 4; break;
        default: return false;
    }
    const std::size_t frameBytes = bytesPerSample * channels;
    const std::size_t frames = byteCount / frameBytes;
    if (encoding == kPcmFloat && channels == 2) {
        float* floatSamples = reinterpret_cast<float*>(data);
        processStereoNeon(floatSamples, frames);
        for (std::size_t i = 0; i < frames * 2; ++i) {
            floatSamples[i] = std::isfinite(floatSamples[i])
                ? std::clamp(floatSamples[i], -1.0F, 1.0F)
                : 0.0F;
        }
        return true;
    }

    for (std::size_t frame = 0; frame < frames; ++frame) {
        std::uint8_t* base = data + frame * frameBytes;
        for (std::size_t ch = 0; ch < channels; ++ch) {
            std::uint8_t* p = base + ch * bytesPerSample;
            switch (encoding) {
                case kPcm16: {
                    std::int16_t raw;
                    std::memcpy(&raw, p, sizeof(raw));
                    const float in = static_cast<float>(raw) / 32768.0F;
                    float out = tick(in, ch);
                    // TPDF dither (+-1 LSB) before requantizing to 16 bits.
                    ditherState_ = ditherState_ * 1664525U + 1013904223U;
                    const float r1 = static_cast<float>(ditherState_ >> 8) / 16777216.0F;
                    ditherState_ = ditherState_ * 1664525U + 1013904223U;
                    const float r2 = static_cast<float>(ditherState_ >> 8) / 16777216.0F;
                    const float scaled = out * 32768.0F + (r1 - r2);
                    const float rounded = std::isfinite(scaled) ? std::nearbyint(scaled) : 0.0F;
                    const auto value = static_cast<std::int16_t>(std::clamp(rounded, -32768.0F, 32767.0F));
                    std::memcpy(p, &value, sizeof(value));
                    break;
                }
                case kPcm24Packed: {
                    const float in = static_cast<float>(readInt24(p)) / 8388608.0F;
                    const double scaled = static_cast<double>(tick(in, ch)) * 8388608.0;
                    const double rounded = std::isfinite(scaled) ? std::nearbyint(scaled) : 0.0;
                    writeInt24(p, static_cast<std::int32_t>(std::clamp(rounded, -8388608.0, 8388607.0)));
                    break;
                }
                case kPcm32: {
                    std::int32_t raw;
                    std::memcpy(&raw, p, sizeof(raw));
                    const double in = static_cast<double>(raw) / 2147483648.0;
                    const double scaled = static_cast<double>(tick(static_cast<float>(in), ch)) * 2147483648.0;
                    const double rounded = std::isfinite(scaled) ? std::nearbyint(scaled) : 0.0;
                    const auto value = static_cast<std::int32_t>(std::clamp(rounded, -2147483648.0, 2147483647.0));
                    std::memcpy(p, &value, sizeof(value));
                    break;
                }
                case kPcmFloat: {
                    float raw;
                    std::memcpy(&raw, p, sizeof(raw));
                    float out = tick(raw, ch);
                    out = std::isfinite(out) ? std::clamp(out, -1.0F, 1.0F) : 0.0F;
                    std::memcpy(p, &out, sizeof(out));
                    break;
                }
                default:
                    break;
            }
        }
    }
    for (std::size_t i = 0; i < activeCount_; ++i) {
        for (std::size_t ch = 0; ch < channels; ++ch) {
            if (!std::isfinite(simdState_[i].z1[ch]) || !std::isfinite(simdState_[i].z2[ch])) {
                simdState_[i].z1[ch] = 0.0f;
                simdState_[i].z2[ch] = 0.0f;
            }
        }
    }
    return true;
}

ParametricEq::Coefficients ParametricEq::design(const Spec& spec, double sampleRate) noexcept {
    Coefficients c;
    const double frequency = clampFrequency(sampleRate, spec.frequency);
    const double omega = 2.0 * kPi * frequency / sampleRate;
    const double cosine = std::cos(omega);
    const double sine = std::sin(omega);
    const double q = std::max(spec.q, 0.05);
    const double alpha = sine / (2.0 * q);
    const double amplitude = std::pow(10.0, spec.gainDb / 40.0);
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;
    switch (spec.type) {
        case kLowShelf: {
            const double twoSqrtAAlpha = 2.0 * std::sqrt(amplitude) * alpha;
            b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + twoSqrtAAlpha);
            b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine);
            b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - twoSqrtAAlpha);
            a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + twoSqrtAAlpha;
            a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine);
            a2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - twoSqrtAAlpha;
            break;
        }
        case kHighShelf: {
            const double twoSqrtAAlpha = 2.0 * std::sqrt(amplitude) * alpha;
            b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + twoSqrtAAlpha);
            b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine);
            b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - twoSqrtAAlpha);
            a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + twoSqrtAAlpha;
            a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine);
            a2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - twoSqrtAAlpha;
            break;
        }
        case kLowPass: {
            b0 = (1.0 - cosine) * 0.5;
            b1 = 1.0 - cosine;
            b2 = (1.0 - cosine) * 0.5;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
        }
        case kHighPass: {
            b0 = (1.0 + cosine) * 0.5;
            b1 = -(1.0 + cosine);
            b2 = (1.0 + cosine) * 0.5;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha;
            break;
        }
        case kPeaking:
        default: {
            b0 = 1.0 + alpha * amplitude;
            b1 = -2.0 * cosine;
            b2 = 1.0 - alpha * amplitude;
            a0 = 1.0 + alpha / amplitude;
            a1 = -2.0 * cosine;
            a2 = 1.0 - alpha / amplitude;
            break;
        }
    }
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

double ParametricEq::magnitude(const Coefficients& c, double sampleRate, double frequency) noexcept {
    const double omega = 2.0 * kPi * clampFrequency(sampleRate, frequency) / sampleRate;
    const double cos1 = std::cos(omega), sin1 = std::sin(omega);
    const double cos2 = std::cos(2.0 * omega), sin2 = std::sin(2.0 * omega);
    const double nr = c.b0 + c.b1 * cos1 + c.b2 * cos2;
    const double ni = -c.b1 * sin1 - c.b2 * sin2;
    const double dr = 1.0 + c.a1 * cos1 + c.a2 * cos2;
    const double di = -c.a1 * sin1 - c.a2 * sin2;
    return std::sqrt((nr * nr + ni * ni) / std::max(dr * dr + di * di, 1.0e-24));
}

double ParametricEq::responseDb(double sampleRate, double frequency) noexcept {
    ParametricEq probe;
    probe.configure(sampleRate, 1);
    if (!probe.enabled_) return 0.0;
    double gain = probe.preampGain_;
    for (std::size_t i = 0; i < probe.activeCount_; ++i) {
        gain *= magnitude(probe.coefficients_[i], sampleRate, frequency);
    }
    return 20.0 * std::log10(std::max(gain, 1.0e-12));
}

}  // namespace clashplayer::audio
