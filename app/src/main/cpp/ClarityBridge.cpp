#include <jni.h>
#include <new>
#include <cstdint>
#include <algorithm>
#include "DspProcessor.h"
#include "LockFreeRingBuffer.h"

using namespace clashplayer::audio;

template class clashplayer::audio::LockFreeRingBuffer<float>;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jdouble sampleRate) {
    auto *processor = new (std::nothrow) DspProcessor();
    if (processor != nullptr) {
        processor->configure(sampleRate);
        processor->setStudioMasterClarity(true);
        processor->setPeakProtectionEnabled(true);
    }
    return reinterpret_cast<jlong>(processor);
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<DspProcessor *>(handle);
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeConfigure(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jdouble sampleRate) {
    if (handle != 0) {
        reinterpret_cast<DspProcessor *>(handle)->configure(sampleRate);
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeReset(
    JNIEnv *env,
    jclass clazz,
    jlong handle) {
    if (handle != 0) {
        reinterpret_cast<DspProcessor *>(handle)->reset();
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeSetStudioMasterClarity(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jboolean enabled) {
    if (handle != 0) {
        reinterpret_cast<DspProcessor *>(handle)->setStudioMasterClarity(enabled);
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeSetClarityWet(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jfloat wet) {
    if (handle != 0) {
        reinterpret_cast<DspProcessor *>(handle)->setClarityWet(wet);
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeSetClarityTrims(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jfloatArray trims) {
    if (handle != 0 && trims != nullptr) {
        jsize len = env->GetArrayLength(trims);
        jfloat *elements = env->GetFloatArrayElements(trims, nullptr);
        if (elements != nullptr) {
            reinterpret_cast<DspProcessor *>(handle)->setClarityTrims(
                elements, static_cast<std::size_t>(len));
            env->ReleaseFloatArrayElements(trims, elements, JNI_ABORT);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeSetClarityPreset(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jint preset) {
    if (handle != 0) {
        reinterpret_cast<DspProcessor *>(handle)->setClarityPreset(preset);
    }
}

JNIEXPORT void JNICALL
Java_com_clashplayer_dsp_ClarityProcessor_nativeProcess(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jfloatArray buffer,
    jint offset,
    jint frameCount,
    jint channels) {
    if (handle == 0 || buffer == nullptr || frameCount <= 0 || channels <= 0) return;
    auto *processor = reinterpret_cast<DspProcessor *>(handle);
    jfloat *samples = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(buffer, nullptr));
    if (samples != nullptr) {
        processor->process(samples + offset, frameCount, channels);
        env->ReleasePrimitiveArrayCritical(buffer, samples, 0);
    }
}

} // extern "C"
