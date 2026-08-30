package tw.nekomimi.nekogram.voice;

import org.telegram.messenger.R;

import java.nio.ByteBuffer;

import xyz.nextalone.nagram.NaConfig;

/**
 * Real-time effects for 16-bit mono capture. Runs on the recording thread, so everything here is
 * per-sample arithmetic over preallocated buffers — no allocation, no FFT, no blocking.
 *
 * Pitch comes from a delay line read at a drifting offset. Two taps half a cycle apart are mixed
 * through Hann windows that sum to exactly one, so the point where a tap wraps past the write head
 * is covered by the other tap at full gain and never turns into a click.
 *
 * Pitch alone makes every preset sound like the same tape running at a different speed, so each
 * one also tilts the spectrum. Shifting the formants properly needs an LPC or cepstral pass that
 * does not fit in this budget; a tilt is the cheap stand-in that still separates a woman's voice
 * from a chipmunk.
 */
public class VoiceChanger {

    public static final int EFFECT_OFF = 0;
    public static final int EFFECT_WOMAN = 1;
    public static final int EFFECT_MAN = 2;
    public static final int EFFECT_CHIPMUNK = 3;
    public static final int EFFECT_DEEP = 4;
    public static final int EFFECT_CHILD = 5;
    public static final int EFFECT_ROBOT = 6;
    public static final int EFFECT_DEMON = 7;
    public static final int EFFECT_ECHO = 8;
    public static final int EFFECT_CAVE = 9;
    public static final int EFFECT_COUNT = 10;

    private static final int SINE_BITS = 12;
    private static final int SINE_SIZE = 1 << SINE_BITS;
    private static final int SINE_MASK = SINE_SIZE - 1;
    private static final float[] SINE = new float[SINE_SIZE];

    static {
        for (int i = 0; i < SINE_SIZE; i++) {
            SINE[i] = (float) Math.sin(2 * Math.PI * i / SINE_SIZE);
        }
    }

    private VoiceChangerNative core;

    private final float[] pitchBuffer;
    private final float phaseStep;
    private int pitchWrite;
    private float phase;

    private final float tilt;
    private float tiltState;
    private final float tiltCoefficient;

    private final float ringStep;
    private final float ringDepth;
    private float ringPhase;

    private final float drive;

    private final float[] echoBuffer;
    private int echoWrite;
    private final float feedback;
    private final float echoMix;

    private final float reverbMix;
    private final float[] combA;
    private final float[] combB;
    private final float[] allPass;
    private int combAIndex;
    private int combBIndex;
    private int allPassIndex;

    public static int currentEffect() {
        int effect = NaConfig.INSTANCE.getVoiceChangerEffect().Int();
        return effect >= 0 && effect < EFFECT_COUNT ? effect : EFFECT_OFF;
    }

    public static boolean isEnabled() {
        return currentEffect() != EFFECT_OFF;
    }

    public static int nameOf(int effect) {
        switch (effect) {
            case EFFECT_WOMAN:
                return R.string.VoiceChangerWoman;
            case EFFECT_MAN:
                return R.string.VoiceChangerMan;
            case EFFECT_CHIPMUNK:
                return R.string.VoiceChangerChipmunk;
            case EFFECT_DEEP:
                return R.string.VoiceChangerDeep;
            case EFFECT_CHILD:
                return R.string.VoiceChangerChild;
            case EFFECT_ROBOT:
                return R.string.VoiceChangerRobot;
            case EFFECT_DEMON:
                return R.string.VoiceChangerDemon;
            case EFFECT_ECHO:
                return R.string.VoiceChangerEcho;
            case EFFECT_CAVE:
                return R.string.VoiceChangerCave;
            default:
                return R.string.VoiceChangerOff;
        }
    }

    /** How much the library may buffer. A call notices anything larger; a round video does not. */
    public static final float LATENCY_CALL = 30f;
    public static final float LATENCY_ROUND = 60f;

    /** Returns null when no effect is selected, so callers can skip the work entirely. */
    public static VoiceChanger create(int sampleRate) {
        return create(sampleRate, LATENCY_CALL);
    }

    public static VoiceChanger create(int sampleRate, float latencyMs) {
        int effect = currentEffect();
        if (effect == EFFECT_OFF) {
            return null;
        }
        VoiceChanger changer = new VoiceChanger(effect, sampleRate);
        changer.core = VoiceChangerNative.create(sampleRate, latencyMs, nativeParams(effect));
        return changer;
    }

    /**
     * Parameters in the order voice_rt reads them. Anything left alone keeps the library's own
     * defaults: unity pitch, unity formant, unity output gain and no other stage engaged.
     */
    private static float[] nativeParams(int effect) {
        float[] params = new float[12];
        params[0] = 1f;
        params[1] = 1f;
        params[11] = 1f;
        switch (effect) {
            case EFFECT_WOMAN:
                params[0] = 1.30f;
                params[1] = 1.22f;
                break;
            case EFFECT_MAN:
                params[0] = 0.80f;
                params[1] = 0.85f;
                break;
            case EFFECT_CHIPMUNK:
                params[0] = 1.45f;
                params[1] = 1.10f;
                break;
            case EFFECT_DEEP:
                params[0] = 0.72f;
                params[1] = 0.92f;
                break;
            case EFFECT_CHILD:
                params[0] = 1.25f;
                params[1] = 1.18f;
                break;
            case EFFECT_ROBOT:
                params[2] = 60f;
                params[3] = 0.7f;
                params[4] = 0.2f;
                break;
            case EFFECT_DEMON:
                params[0] = 0.55f;
                params[1] = 0.80f;
                params[4] = 0.25f;
                params[9] = 0.30f;
                break;
            case EFFECT_ECHO:
                params[6] = 220f;
                params[7] = 0.40f;
                params[8] = 0.35f;
                break;
            case EFFECT_CAVE:
                params[0] = 0.90f;
                params[6] = 120f;
                params[7] = 0.30f;
                params[8] = 0.25f;
                params[9] = 0.55f;
                break;
            default:
                break;
        }
        return params;
    }

    /** Frees the library's stream. Safe on an instance that never got one. */
    public void release() {
        if (core != null) {
            core.release();
            core = null;
        }
    }

    private VoiceChanger(int effect, int sampleRate) {
        float pitch = 1f;
        float formant = 1f;
        float ringHz = 0f;
        float ringAmount = 0f;
        float driveAmount = 0f;
        int delayMillis = 0;
        float feedbackAmount = 0f;
        float echoAmount = 0f;
        float reverbAmount = 0f;

        switch (effect) {
            case EFFECT_WOMAN:
                pitch = 1.30f;
                formant = 1.22f;
                break;
            case EFFECT_MAN:
                pitch = 0.80f;
                formant = 0.85f;
                break;
            case EFFECT_CHIPMUNK:
                pitch = 1.45f;
                formant = 1.10f;
                break;
            case EFFECT_DEEP:
                pitch = 0.72f;
                formant = 0.92f;
                break;
            case EFFECT_CHILD:
                pitch = 1.25f;
                formant = 1.18f;
                break;
            case EFFECT_ROBOT:
                ringHz = 60f;
                ringAmount = 0.7f;
                driveAmount = 0.2f;
                break;
            case EFFECT_DEMON:
                pitch = 0.55f;
                formant = 0.80f;
                driveAmount = 0.25f;
                reverbAmount = 0.30f;
                break;
            case EFFECT_ECHO:
                delayMillis = 220;
                feedbackAmount = 0.40f;
                echoAmount = 0.35f;
                break;
            case EFFECT_CAVE:
                pitch = 0.90f;
                formant = 0.95f;
                delayMillis = 120;
                feedbackAmount = 0.30f;
                echoAmount = 0.25f;
                reverbAmount = 0.55f;
                break;
            default:
                break;
        }

        if (pitch == 1f) {
            pitchBuffer = null;
            phaseStep = 0f;
        } else {
            // ~32 ms of history: long enough that the grain cycle stays under the voice's own
            // rhythm, short enough that a call does not gain audible delay
            int length = Math.max(512, sampleRate * 32 / 1000);
            pitchBuffer = new float[length];
            phaseStep = (1f - pitch) / length;
        }

        tilt = clamp((formant - 1f) * 2.2f, -0.9f, 1.6f);
        tiltCoefficient = 1f - (float) Math.exp(-2 * Math.PI * 1400f / sampleRate);

        ringStep = ringHz == 0 ? 0 : ringHz * SINE_SIZE / sampleRate;
        ringDepth = ringAmount;
        drive = driveAmount;

        int delaySamples = delayMillis * sampleRate / 1000;
        echoBuffer = delaySamples == 0 ? null : new float[delaySamples];
        feedback = feedbackAmount;
        echoMix = echoAmount;

        reverbMix = reverbAmount;
        if (reverbAmount == 0) {
            combA = combB = allPass = null;
        } else {
            combA = new float[sampleRate * 37 / 1000];
            combB = new float[sampleRate * 43 / 1000];
            allPass = new float[sampleRate * 5 / 1000];
        }
    }

    /**
     * Rewrites {@code lengthBytes} of 16-bit samples in place. Uses absolute access so the
     * buffer's own position and limit are left exactly as they were.
     */
    public void process(ByteBuffer buffer, int lengthBytes) {
        if (core != null) {
            core.process(buffer, lengthBytes);
            return;
        }
        int samples = lengthBytes / 2;
        for (int i = 0; i < samples; i++) {
            int index = i * 2;
            float sample = buffer.getShort(index);

            if (pitchBuffer != null) {
                sample = shiftPitch(sample);
            }
            if (tilt != 0f) {
                tiltState += tiltCoefficient * (sample - tiltState);
                sample += tilt * (sample - tiltState);
            }
            if (ringStep != 0f) {
                float modulation = 1f - ringDepth + ringDepth * sine(ringPhase);
                sample *= modulation;
                ringPhase += ringStep;
                if (ringPhase >= SINE_SIZE) {
                    ringPhase -= SINE_SIZE;
                }
            }
            if (drive != 0f) {
                sample = saturate(sample, drive);
            }
            if (echoBuffer != null) {
                sample = applyEcho(sample);
            }
            if (combA != null) {
                sample = applyReverb(sample);
            }

            buffer.putShort(index, clip(sample));
        }
    }

    private float shiftPitch(float sample) {
        int length = pitchBuffer.length;
        pitchBuffer[pitchWrite] = sample;
        pitchWrite = pitchWrite + 1 == length ? 0 : pitchWrite + 1;

        float other = phase + 0.5f;
        if (other >= 1f) {
            other -= 1f;
        }
        // the two windows sum to one at every phase, so no seam survives the crossfade
        float out = read(phase * length) * hann(phase) + read(other * length) * hann(other);

        phase += phaseStep;
        if (phase >= 1f) {
            phase -= 1f;
        } else if (phase < 0f) {
            phase += 1f;
        }
        return out;
    }

    private float read(float delay) {
        int length = pitchBuffer.length;
        float position = pitchWrite - 1 - delay;
        while (position < 0) {
            position += length;
        }
        int index = (int) position;
        int next = index + 1 == length ? 0 : index + 1;
        float fraction = position - index;
        return pitchBuffer[index] * (1f - fraction) + pitchBuffer[next] * fraction;
    }

    private float applyEcho(float sample) {
        float delayed = echoBuffer[echoWrite];
        echoBuffer[echoWrite] = sample + delayed * feedback;
        echoWrite = echoWrite + 1 == echoBuffer.length ? 0 : echoWrite + 1;
        return sample + delayed * echoMix;
    }

    private float applyReverb(float sample) {
        float a = combA[combAIndex];
        combA[combAIndex] = sample + a * 0.78f;
        combAIndex = combAIndex + 1 == combA.length ? 0 : combAIndex + 1;

        float b = combB[combBIndex];
        combB[combBIndex] = sample + b * 0.73f;
        combBIndex = combBIndex + 1 == combB.length ? 0 : combBIndex + 1;

        float wet = (a + b) * 0.5f;
        float stored = allPass[allPassIndex];
        float passed = -wet + stored;
        allPass[allPassIndex] = wet + stored * 0.6f;
        allPassIndex = allPassIndex + 1 == allPass.length ? 0 : allPassIndex + 1;

        return sample * (1f - reverbMix * 0.5f) + passed * reverbMix;
    }

    private static float saturate(float sample, float amount) {
        float normalized = sample / 32768f;
        float gain = 1f + amount * 6f;
        float shaped = normalized * gain / (1f + Math.abs(normalized * gain));
        return shaped * 32768f * (1f - amount * 0.35f);
    }

    private static float hann(float phase) {
        return 0.5f - 0.5f * cosine(phase);
    }

    /** phase is a fraction of a full turn, not radians */
    private static float cosine(float phase) {
        return sine((phase + 0.25f) * SINE_SIZE);
    }

    private static float sine(float phase) {
        return SINE[((int) phase) & SINE_MASK];
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static short clip(float sample) {
        if (sample > 32767f) {
            return 32767;
        }
        if (sample < -32768f) {
            return -32768;
        }
        return (short) sample;
    }
}
