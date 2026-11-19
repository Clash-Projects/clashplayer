// Native float PCM outputs: AAudio (blocking writes) and OpenSL ES (buffer queue fed from a ring buffer).
#include <jni.h>
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define API_AAUDIO 1
#define API_OPENSL 2
#define SL_BUFFERS 4

typedef struct {
    int api;
    int channels;
    AAudioStream *aa;
    SLObjectItf engineObj, mixObj, playerObj;
    SLPlayItf play;
    SLAndroidSimpleBufferQueueItf queue;
    float *ring;            // interleaved, ringFrames frames
    int ringFrames;
    long long rd, wr;       // monotonic frame counters
    float *bufs[SL_BUFFERS];
    int bufFrames, next;
    long long played;       // frames handed to the device
    pthread_mutex_t mu;
    pthread_cond_t cv;
    int closing;
} Out;

static void sl_fill(SLAndroidSimpleBufferQueueItf q, void *ctx) {
    Out *o = ctx;
    float *b = o->bufs[o->next];
    o->next = (o->next + 1) % SL_BUFFERS;
    pthread_mutex_lock(&o->mu);
    long long avail = o->wr - o->rd;
    int n = avail < o->bufFrames ? (int) avail : o->bufFrames;
    for (int f = 0; f < n; f++) {
        int i = (int) ((o->rd + f) % o->ringFrames);
        memcpy(b + (size_t) f * o->channels, o->ring + (size_t) i * o->channels, sizeof(float) * o->channels);
    }
    o->rd += n;
    o->played += n;
    pthread_cond_signal(&o->cv);
    pthread_mutex_unlock(&o->mu);
    // Underrun: pad with silence rather than stalling the queue
    if (n < o->bufFrames) memset(b + (size_t) n * o->channels, 0, sizeof(float) * (size_t) (o->bufFrames - n) * o->channels);
    (*q)->Enqueue(q, b, sizeof(float) * (SLuint32) o->bufFrames * o->channels);
}

static void sl_destroy(Out *o) {
    if (o->playerObj) (*o->playerObj)->Destroy(o->playerObj);
    if (o->mixObj) (*o->mixObj)->Destroy(o->mixObj);
    if (o->engineObj) (*o->engineObj)->Destroy(o->engineObj);
    for (int i = 0; i < SL_BUFFERS; i++) free(o->bufs[i]);
    free(o->ring);
}

static int sl_open(Out *o, int rate, int bufferMs) {
    SLEngineItf engine;
    if (slCreateEngine(&o->engineObj, 0, NULL, 0, NULL, NULL) != SL_RESULT_SUCCESS) return -1;
    if ((*o->engineObj)->Realize(o->engineObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return -2;
    (*o->engineObj)->GetInterface(o->engineObj, SL_IID_ENGINE, &engine);
    if ((*engine)->CreateOutputMix(engine, &o->mixObj, 0, NULL, NULL) != SL_RESULT_SUCCESS) return -3;
    if ((*o->mixObj)->Realize(o->mixObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return -4;

    SLDataLocator_AndroidSimpleBufferQueue loc = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, SL_BUFFERS};
    SLAndroidDataFormat_PCM_EX fmt = {
        SL_ANDROID_DATAFORMAT_PCM_EX, (SLuint32) o->channels, (SLuint32) rate * 1000,
        32, 32, o->channels == 1 ? SL_SPEAKER_FRONT_CENTER : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT),
        SL_BYTEORDER_LITTLEENDIAN, SL_ANDROID_PCM_REPRESENTATION_FLOAT};
    SLDataSource src = {&loc, &fmt};
    SLDataLocator_OutputMix mixLoc = {SL_DATALOCATOR_OUTPUTMIX, o->mixObj};
    SLDataSink sink = {&mixLoc, NULL};
    const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[] = {SL_BOOLEAN_TRUE};
    if ((*engine)->CreateAudioPlayer(engine, &o->playerObj, &src, &sink, 1, ids, req) != SL_RESULT_SUCCESS) return -5;
    if ((*o->playerObj)->Realize(o->playerObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return -6;
    (*o->playerObj)->GetInterface(o->playerObj, SL_IID_PLAY, &o->play);
    (*o->playerObj)->GetInterface(o->playerObj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &o->queue);
    (*o->queue)->RegisterCallback(o->queue, sl_fill, o);

    o->bufFrames = rate / 100; // 10 ms per buffer
    for (int i = 0; i < SL_BUFFERS; i++) {
        o->bufs[i] = calloc((size_t) o->bufFrames * o->channels, sizeof(float));
        if (!o->bufs[i]) return -7;
    }
    o->ringFrames = rate * bufferMs / 1000;
    o->ring = calloc((size_t) o->ringFrames * o->channels, sizeof(float));
    if (!o->ring) return -7;
    for (int i = 0; i < SL_BUFFERS; i++)
        (*o->queue)->Enqueue(o->queue, o->bufs[i], sizeof(float) * (SLuint32) o->bufFrames * o->channels);
    o->next = 0;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativeOpen(JNIEnv *env, jclass cls, jint api, jint rate, jint channels, jint bufferMs) {
    Out *o = calloc(1, sizeof(Out));
    if (!o) return 0;
    o->api = api;
    o->channels = channels;
    pthread_mutex_init(&o->mu, NULL);
    pthread_cond_init(&o->cv, NULL);
    if (api == API_AAUDIO) {
        AAudioStreamBuilder *b;
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) { free(o); return 0; }
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setSampleRate(b, rate);
        AAudioStreamBuilder_setChannelCount(b, channels);
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
        if (__builtin_available(android 28, *)) {
            AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
            AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
        }
        aaudio_result_t r = AAudioStreamBuilder_openStream(b, &o->aa);
        AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK) { free(o); return 0; }
        int burst = AAudioStream_getFramesPerBurst(o->aa);
        int want = rate * bufferMs / 1000;
        AAudioStream_setBufferSizeInFrames(o->aa, want > burst ? want : burst);
        return (jlong) (intptr_t) o;
    }
    if (sl_open(o, rate, bufferMs) != 0) { sl_destroy(o); free(o); return 0; }
    return (jlong) (intptr_t) o;
}

/** Writes count interleaved samples, blocking until queued. Returns frames written, or a negative error. */
JNIEXPORT jint JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativeWrite(JNIEnv *env, jclass cls, jlong h, jfloatArray data, jint count) {
    Out *o = (Out *) (intptr_t) h;
    int frames = count / o->channels;
    jfloat *src = (*env)->GetFloatArrayElements(env, data, NULL);
    int done = 0;
    if (o->api == API_AAUDIO) {
        while (done < frames) {
            aaudio_result_t r = AAudioStream_write(o->aa, src + (size_t) done * o->channels, frames - done, 1000000000LL);
            if (r < 0) { done = r; break; }
            if (r == 0) break; // timed out: paused or stalled
            done += r;
        }
    } else {
        pthread_mutex_lock(&o->mu);
        while (done < frames && !o->closing) {
            long long space = o->ringFrames - (o->wr - o->rd);
            if (space <= 0) {
                struct timespec ts;
                clock_gettime(CLOCK_REALTIME, &ts);
                ts.tv_nsec += 200000000L;
                if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
                if (pthread_cond_timedwait(&o->cv, &o->mu, &ts) != 0) break; // paused: return what fit
                continue;
            }
            int n = frames - done < space ? frames - done : (int) space;
            for (int f = 0; f < n; f++) {
                int i = (int) ((o->wr + f) % o->ringFrames);
                memcpy(o->ring + (size_t) i * o->channels, src + (size_t) (done + f) * o->channels, sizeof(float) * o->channels);
            }
            o->wr += n;
            done += n;
        }
        pthread_mutex_unlock(&o->mu);
    }
    (*env)->ReleaseFloatArrayElements(env, data, src, JNI_ABORT);
    return done;
}

JNIEXPORT jint JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativeStart(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    if (o->api == API_AAUDIO) return AAudioStream_requestStart(o->aa);
    return (*o->play)->SetPlayState(o->play, SL_PLAYSTATE_PLAYING) == SL_RESULT_SUCCESS ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativePause(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    if (o->api == API_AAUDIO) return AAudioStream_requestPause(o->aa);
    return (*o->play)->SetPlayState(o->play, SL_PLAYSTATE_PAUSED) == SL_RESULT_SUCCESS ? 0 : -1;
}

/** Drops queued audio; the stream must be paused. */
JNIEXPORT jint JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativeFlush(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    if (o->api == API_AAUDIO) return AAudioStream_requestFlush(o->aa);
    pthread_mutex_lock(&o->mu);
    o->rd = o->wr;
    pthread_cond_signal(&o->cv);
    pthread_mutex_unlock(&o->mu);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativeFramesPlayed(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    if (o->api == API_AAUDIO) return AAudioStream_getFramesRead(o->aa);
    pthread_mutex_lock(&o->mu);
    long long p = o->played;
    pthread_mutex_unlock(&o->mu);
    return p;
}

JNIEXPORT void JNICALL
Java_com_clashplayer_service_NativeAudioOutput_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    if (o->api == API_AAUDIO) {
        AAudioStream_requestStop(o->aa);
        AAudioStream_close(o->aa);
    } else {
        pthread_mutex_lock(&o->mu);
        o->closing = 1;
        pthread_cond_broadcast(&o->cv);
        pthread_mutex_unlock(&o->mu);
        (*o->play)->SetPlayState(o->play, SL_PLAYSTATE_STOPPED);
        sl_destroy(o);
    }
    pthread_cond_destroy(&o->cv);
    pthread_mutex_destroy(&o->mu);
    free(o);
}
