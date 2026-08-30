# voice_rt

Prebuilt voice-processing library used by the voice changer, kept apart from `jni/` so the
Telegram native tree stays untouched.

```
jniLibs/arm64-v8a/libvoice_rt.so
jniLibs/armeabi-v7a/libvoice_rt.so
vc_jni.c                            bridge to Java
```

Written in Rust, built with NDK r25c, ABI version 3. Obtained from its author (@mraniv,
@anivplugins) for free redistribution.

## What it does

Pitch and formant are separate parameters, which is what keeps a shifted voice sounding like a
voice instead of a sped-up tape. It works in the time domain — the exported surface has no FFT
and pulls only `sinf`, `sincosf`, `expf` and `powf` from libm — and streams through a ring buffer
at a fixed latency, nudging its rate by fractions of a per mille to keep from running dry.

## Interface

```c
int   vc_abi_version(void);                              // 3
void *vc_create_ex(int sample_rate, float latency_ms);
void  vc_destroy(void *stream);
void  vc_set_params(void *stream, const VcParams *params);
int   vc_process_i16(void *stream, short *samples, int count);   // in place
void  vc_reset(void *stream);
int   vc_latency_frames(void *stream);
```

`VcParams` is twelve floats followed by an int:

```
pitch  formant  ring_hz  ring_depth  drive  chorus
delay_ms  feedback  echo_mix  reverb  gate_db  output_gain  bypass
```

Defaults are unity pitch, unity formant, unity output gain, and zero everywhere else.

## Missing ABIs

Only arm64-v8a and armeabi-v7a exist. `vc_jni.c` resolves everything through `dlopen`, so an x86
build links and runs; `VoiceChangerNative` reports the library as unavailable and `VoiceChanger`
falls back to its own effects.
