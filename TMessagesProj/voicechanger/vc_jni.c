// Bridge to the voice_rt library. Everything is resolved with dlopen at first use, so a build
// for an ABI the library was never compiled for still links and simply reports itself
// unavailable, and the Java side falls back to its own effects.

#include <jni.h>
#include <dlfcn.h>
#include <stddef.h>

#define VC_ABI_VERSION 3

typedef struct {
    float pitch;
    float formant;
    float ring_hz;
    float ring_depth;
    float drive;
    float chorus;
    float delay_ms;
    float feedback;
    float echo_mix;
    float reverb;
    float gate_db;
    float output_gain;
    int bypass;
} VcParams;

static void *library;
static int loaded;

static int (*vc_abi_version)(void);
static void *(*vc_create_ex)(int sample_rate, float latency_ms);
static void (*vc_destroy)(void *stream);
static void (*vc_set_params)(void *stream, const VcParams *params);
static int (*vc_process_i16)(void *stream, short *samples, int count);
static void (*vc_reset)(void *stream);
static int (*vc_latency_frames)(void *stream);

static int load(void) {
    if (loaded) {
        return library != NULL;
    }
    loaded = 1;

    library = dlopen("libvoice_rt.so", RTLD_NOW);
    if (!library) {
        return 0;
    }

    vc_abi_version = dlsym(library, "vc_abi_version");
    vc_create_ex = dlsym(library, "vc_create_ex");
    vc_destroy = dlsym(library, "vc_destroy");
    vc_set_params = dlsym(library, "vc_set_params");
    vc_process_i16 = dlsym(library, "vc_process_i16");
    vc_reset = dlsym(library, "vc_reset");
    vc_latency_frames = dlsym(library, "vc_latency_frames");

    int complete = vc_abi_version && vc_create_ex && vc_destroy && vc_set_params
                   && vc_process_i16 && vc_reset && vc_latency_frames;
    // a mismatched ABI means the struct below no longer describes what the library reads
    if (!complete || vc_abi_version() != VC_ABI_VERSION) {
        dlclose(library);
        library = NULL;
        return 0;
    }
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeAvailable(JNIEnv *env, jclass clazz) {
    return load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeCreate(JNIEnv *env, jclass clazz, jint sampleRate, jfloat latencyMs) {
    if (!load()) {
        return 0;
    }
    return (jlong) (intptr_t) vc_create_ex(sampleRate, latencyMs);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle && load()) {
        vc_destroy((void *) (intptr_t) handle);
    }
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeReset(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle && load()) {
        vc_reset((void *) (intptr_t) handle);
    }
}

JNIEXPORT jint JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeLatencyFrames(JNIEnv *env, jclass clazz, jlong handle) {
    if (!handle || !load()) {
        return 0;
    }
    return vc_latency_frames((void *) (intptr_t) handle);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeSetParams(JNIEnv *env, jclass clazz, jlong handle, jfloatArray values, jint bypass) {
    if (!handle || !load()) {
        return;
    }
    if ((*env)->GetArrayLength(env, values) < 12) {
        return;
    }
    jfloat *raw = (*env)->GetFloatArrayElements(env, values, NULL);
    if (!raw) {
        return;
    }
    VcParams params;
    params.pitch = raw[0];
    params.formant = raw[1];
    params.ring_hz = raw[2];
    params.ring_depth = raw[3];
    params.drive = raw[4];
    params.chorus = raw[5];
    params.delay_ms = raw[6];
    params.feedback = raw[7];
    params.echo_mix = raw[8];
    params.reverb = raw[9];
    params.gate_db = raw[10];
    params.output_gain = raw[11];
    params.bypass = bypass;
    (*env)->ReleaseFloatArrayElements(env, values, raw, JNI_ABORT);

    vc_set_params((void *) (intptr_t) handle, &params);
}

// The capture buffers are direct, so the samples are rewritten where they already live.
JNIEXPORT jint JNICALL
Java_tw_nekomimi_nekogram_voice_VoiceChangerNative_nativeProcess(JNIEnv *env, jclass clazz, jlong handle, jobject buffer, jint sampleCount) {
    if (!handle || !load() || sampleCount <= 0) {
        return 0;
    }
    void *address = (*env)->GetDirectBufferAddress(env, buffer);
    if (!address) {
        return 0;
    }
    return vc_process_i16((void *) (intptr_t) handle, (short *) address, sampleCount);
}
