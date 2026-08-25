package tw.nekomimi.nekogram.voice;

import org.telegram.messenger.R;

import java.nio.ByteBuffer;

import xyz.nextalone.nagram.NaConfig;

/**
 * Real-time effects for 16-bit mono capture. Runs on the recording thread, so everything here is
 * per-sample arithmetic over preallocated buffers — no allocation, no FFT, no blocking.
 *
 * Pitch shifting uses two delay taps half a buffer apart, crossfaded by position. That is far
 * cheaper than a phase vocoder and its slight roughness is what makes these sound like voice
 * effects rather than a transposed recording.
 */
public class VoiceChanger {

    public static final int EFFECT_OFF = 0;
    public static final int EFFECT_MAN = 1;
    public static final int EFFECT_WOMAN = 2;
    public static final int EFFECT_CHIPMUNK = 3;
    public static final int EFFECT_MONSTER = 4;
    public static final int EFFECT_ROBOT = 5;
    public static final int EFFECT_ECHO = 6;
    public static final int EFFECT_CAVE = 7;
    public static final int EFFECT_ALIEN = 8;
    public static final int EFFECT_COUNT = 9;

    private static final int SINE_BITS = 12;
    private static final int SINE_SIZE = 1 << SINE_BITS;
    private static final int SINE_MASK = SINE_SIZE - 1;
    private static final float[] SINE = new float[SINE_SIZE];

    static {
        for (int i = 0; i < SINE_SIZE; i++) {
            SINE[i] = (float) Math.sin(2 * Math.PI * i / SINE_SIZE);
        }
    }

    private final float pitchRatio;
    private final float[] pitchBuffer;
    private int pitchWrite;
    private float pitchRead;

    private final float[] echoBuffer;
    private int echoWrite;
    private final float echoFeedback;
    private final float echoMix;

    private final float modStep;
    private float modPhase;

    private final float drive;

    public static int currentEffect() {
        int effect = NaConfig.INSTANCE.getVoiceChangerEffect().Int();
        return effect >= 0 && effect < EFFECT_COUNT ? effect : EFFECT_OFF;
    }

    public static boolean isEnabled() {
        return currentEffect() != EFFECT_OFF;
    }

    public static int nameOf(int effect) {
        switch (effect) {
            case EFFECT_MAN:
                return R.string.VoiceChangerMan;
            case EFFECT_WOMAN:
                return R.string.VoiceChangerWoman;
            case EFFECT_CHIPMUNK:
                return R.string.VoiceChangerChipmunk;
            case EFFECT_MONSTER:
                return R.string.VoiceChangerMonster;
            case EFFECT_ROBOT:
                return R.string.VoiceChangerRobot;
            case EFFECT_ECHO:
                return R.string.VoiceChangerEcho;
            case EFFECT_CAVE:
                return R.string.VoiceChangerCave;
            case EFFECT_ALIEN:
                return R.string.VoiceChangerAlien;
            default:
                return R.string.VoiceChangerOff;
        }
    }

    /** Returns null when no effect is selected, so callers can skip the work entirely. */
    public static VoiceChanger create(int sampleRate) {
        int effect = currentEffect();
        return effect == EFFECT_OFF ? null : new VoiceChanger(effect, sampleRate);
    }

    private VoiceChanger(int effect, int sampleRate) {
        pitchRatio = pitchRatioFor(effect);
        pitchBuffer = pitchRatio == 1f ? null : new float[Math.max(512, sampleRate / 20)];

        int echoSamples = echoMillisFor(effect) * sampleRate / 1000;
        echoBuffer = echoSamples == 0 ? null : new float[echoSamples];
        echoFeedback = effect == EFFECT_CAVE ? 0.55f : 0.38f;
        echoMix = effect == EFFECT_CAVE ? 0.6f : 0.45f;

        float modHz = modHzFor(effect);
        modStep = modHz == 0 ? 0 : modHz * SINE_SIZE / sampleRate;

        drive = effect == EFFECT_MONSTER ? 1.8f : 1f;
    }

    private static float pitchRatioFor(int effect) {
        switch (effect) {
            case EFFECT_MAN:
                return 0.80f;
            case EFFECT_WOMAN:
                return 1.28f;
            case EFFECT_CHIPMUNK:
                return 1.65f;
            case EFFECT_MONSTER:
                return 0.62f;
            case EFFECT_ALIEN:
                return 1.35f;
            default:
                return 1f;
        }
    }

    private static int echoMillisFor(int effect) {
        switch (effect) {
            case EFFECT_ECHO:
                return 260;
            case EFFECT_CAVE:
                return 520;
            default:
                return 0;
        }
    }

    private static float modHzFor(int effect) {
        switch (effect) {
            case EFFECT_ROBOT:
                return 70f;
            case EFFECT_ALIEN:
                return 145f;
            default:
                return 0f;
        }
    }

    /**
     * Rewrites {@code lengthBytes} of 16-bit samples in place. Uses absolute access so the
     * buffer's own position and limit are left exactly as they were.
     */
    public void process(ByteBuffer buffer, int lengthBytes) {
        int samples = lengthBytes / 2;
        for (int i = 0; i < samples; i++) {
            int index = i * 2;
            float sample = buffer.getShort(index);

            if (pitchBuffer != null) {
                sample = shiftPitch(sample);
            }
            if (modStep != 0) {
                sample *= sine(modPhase);
                modPhase += modStep;
                if (modPhase >= SINE_SIZE) {
                    modPhase -= SINE_SIZE;
                }
            }
            if (echoBuffer != null) {
                sample = applyEcho(sample);
            }
            if (drive != 1f) {
                sample = saturate(sample * drive);
            }

            buffer.putShort(index, clip(sample));
        }
    }

    private float shiftPitch(float sample) {
        int length = pitchBuffer.length;
        pitchBuffer[pitchWrite] = sample;
        pitchWrite = pitchWrite + 1 == length ? 0 : pitchWrite + 1;

        float second = pitchRead + length / 2f;
        if (second >= length) {
            second -= length;
        }
        // the taps fade in and out in antiphase, so the seam where one wraps is never audible
        float position = pitchRead / length;
        float gain = 0.5f - 0.5f * sine((position + 0.75f) * SINE_SIZE);
        float out = tap(pitchRead) * gain + tap(second) * (1f - gain);

        pitchRead += pitchRatio;
        while (pitchRead >= length) {
            pitchRead -= length;
        }
        return out;
    }

    private float tap(float position) {
        int length = pitchBuffer.length;
        int index = (int) position;
        int next = index + 1 == length ? 0 : index + 1;
        float fraction = position - index;
        return pitchBuffer[index] * (1f - fraction) + pitchBuffer[next] * fraction;
    }

    private float applyEcho(float sample) {
        float delayed = echoBuffer[echoWrite];
        float out = sample + delayed * echoMix;
        echoBuffer[echoWrite] = sample + delayed * echoFeedback;
        echoWrite = echoWrite + 1 == echoBuffer.length ? 0 : echoWrite + 1;
        return out;
    }

    private static float saturate(float sample) {
        float normalized = sample / 32768f;
        float shaped = normalized / (1f + Math.abs(normalized));
        return shaped * 32768f * 1.6f;
    }

    private static float sine(float phase) {
        return SINE[((int) phase) & SINE_MASK];
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
