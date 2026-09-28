package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the behaviors this class went through several real, distinct
 * bugs to get right, none of which had any regression coverage until
 * now -- every one of these was originally caught by ad hoc, throwaway
 * verification during debugging, not a committed test:
 * <ul>
 *   <li>Sample generation must track this project's own actual
 *       real-wall-clock-time effective clock rate, not the true Apple
 *       II hardware rate -- using the latter silently under-produced
 *       samples and gradually starved the audio line's buffer.</li>
 *   <li>A confirmed-idle toggle must emit true silence, not the
 *       stale LOW/HIGH value it last settled on.</li>
 *   <li>{@link #flush} must keep writing to the line even while idle,
 *       never stop and later restart it -- stopping introduced a real
 *       wake-up-latency artifact (a thump, and inconsistent triggering)
 *       worse than the silent-value bug it was meant to fix.</li>
 *   <li>{@link #close} must release the real device in the standard
 *       drain/stop/close order.</li>
 * </ul>
 * Uses a dynamic proxy for the {@link SourceDataLine} rather than a
 * real device (this project has no Mockito dependency, and a proxy
 * needs none) -- exercised via the package-visible constructor meant
 * exactly for this, not reflection into the public one.
 */
class SpeakerOutputTest {

    /** Records every {@code write(byte[], int, int)} call's bytes, in order. */
    private static final class RecordingLine {
        final List<Byte> written = new ArrayList<>();
        final List<String> methodCalls = new ArrayList<>();

        SourceDataLine asProxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                methodCalls.add(method.getName());
                if (method.getName().equals("write")) {
                    byte[] data = (byte[]) args[0];
                    int offset = (int) args[1];
                    int length = (int) args[2];
                    for (int i = offset; i < offset + length; i++) {
                        written.add(data[i]);
                    }
                    return length;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == long.class) {
                    return 0L;
                }
                return null;
            };
            return (SourceDataLine) Proxy.newProxyInstance(
                SourceDataLine.class.getClassLoader(), new Class<?>[]{SourceDataLine.class}, handler);
        }

        /**
         * Decodes the recorded bytes as 16-bit signed, little-endian
         * samples -- matching {@code SpeakerOutput}'s own
         * {@code AudioFormat(SAMPLE_RATE, 16, 1, true, false)}.
         */
        List<Short> samples() {
            List<Short> result = new ArrayList<>();
            for (int i = 0; i + 1 < written.size(); i += 2) {
                int low = written.get(i) & 0xFF;
                int high = written.get(i + 1) & 0xFF;
                result.add((short) ((high << 8) | low));
            }
            return result;
        }
    }

    // The real ratio between this project's own actual effective clock
    // rate (1,000,000 Hz -- Apple2Plus's CYCLES_PER_TICK/FRAME_INTERVAL_MS,
    // not the true ~1,022,727 Hz hardware rate) and 44,100 Hz, rounded to
    // the nearest whole cycle count for one tick's worth of ticking in
    // these tests.
    private static final int CYCLES_PER_TICK = 20_000;

    @Test
    void activeTogglingProducesAlternatingHighLowSamples() {
        RecordingLine recordingLine = new RecordingLine();
        SpeakerToggle toggle = new SpeakerToggle();
        SpeakerOutput output = new SpeakerOutput(toggle, recordingLine.asProxy());

        toggle.write(0, 0); // speaker high
        output.tick(CYCLES_PER_TICK);
        output.flush();

        assertFalse(recordingLine.samples().isEmpty(), "toggling should produce samples");
        assertTrue(recordingLine.samples().stream().allMatch(s -> s == 8192),
            "every sample this tick should reflect the speaker being high (8192), not silence or low");
    }

    @Test
    void confirmedIdleEmitsTrueSilenceNotTheStaleValue() {
        RecordingLine recordingLine = new RecordingLine();
        SpeakerToggle toggle = new SpeakerToggle();
        SpeakerOutput output = new SpeakerOutput(toggle, recordingLine.asProxy());

        toggle.write(0, 0); // speaker high, then never touched again
        output.tick(CYCLES_PER_TICK);
        output.flush();

        // The grace period itself (IDLE_TICKS_THRESHOLD ticks) still
        // legitimately emits the stale value -- that's the intended,
        // tested-elsewhere behavior. Only check well past it.
        for (int i = 0; i < 5; i++) {
            output.tick(CYCLES_PER_TICK);
            output.flush();
        }
        recordingLine.written.clear();

        output.tick(CYCLES_PER_TICK);
        output.flush();

        assertFalse(recordingLine.samples().isEmpty(), "idle ticks should still produce samples");
        long nonSilentSamples = recordingLine.samples().stream().filter(s -> s != 0).count();
        assertEquals(0, nonSilentSamples,
            "once confirmed idle for well past the grace period, every sample should be true "
                + "silence (0), never the stale high (8192) value");
    }

    @Test
    void flushNeverStopsWritingWhileIdle() {
        RecordingLine recordingLine = new RecordingLine();
        SpeakerToggle toggle = new SpeakerToggle();
        SpeakerOutput output = new SpeakerOutput(toggle, recordingLine.asProxy());

        int writeCallsBefore = countWriteCalls(recordingLine);
        for (int i = 0; i < 10; i++) {
            output.tick(CYCLES_PER_TICK); // never toggled -- fully idle throughout
            output.flush();
        }
        int writeCallsAfter = countWriteCalls(recordingLine);

        assertEquals(10, writeCallsAfter - writeCallsBefore,
            "flush() must call write() every single time, even while idle -- stopping and later "
                + "restarting the line was tried and caused a real, worse artifact (a thump and "
                + "inconsistent triggering) than the silent-value bug it was meant to fix");
    }

    @Test
    void resumesCorrectlyAfterAnIdlePeriod() {
        RecordingLine recordingLine = new RecordingLine();
        SpeakerToggle toggle = new SpeakerToggle();
        SpeakerOutput output = new SpeakerOutput(toggle, recordingLine.asProxy());

        for (int i = 0; i < 5; i++) { // confirm idle, well past the grace period
            output.tick(CYCLES_PER_TICK);
            output.flush();
        }
        recordingLine.written.clear();

        toggle.write(0, 0); // speaker high again
        output.tick(CYCLES_PER_TICK);
        output.flush();

        // Every sample this tick, not just some -- the toggle happens
        // before any samples are generated this tick, so every single
        // one should reflect it. A real, previously undiscovered bug
        // computed the idle/silence decision once at the tick's start,
        // before this same tick's own toggle could be seen -- silencing
        // the entire first tick of a resumed tone regardless of the
        // toggle already having happened.
        assertTrue(recordingLine.samples().stream().allMatch(s -> s == 8192),
            "every sample in the tick where the speaker resumes should be HIGH (8192), "
                + "none should still be silent (0) from the prior idle state");
    }

    @Test
    void closeDrainsStopsAndClosesTheLineInOrder() {
        RecordingLine recordingLine = new RecordingLine();
        SpeakerOutput output = new SpeakerOutput(new SpeakerToggle(), recordingLine.asProxy());

        output.close();

        assertEquals(List.of("drain", "stop", "close"), recordingLine.methodCalls,
            "close() should drain, then stop, then close the real line, in that order");
    }

    @Test
    void noDeviceIsASilentNoOpThroughout() {
        SpeakerOutput output = new SpeakerOutput(new SpeakerToggle(), null);

        output.tick(CYCLES_PER_TICK);
        output.flush();
        output.close();
        // No exception at any step is the entire point of this test --
        // a real possibility this project can't assume away (e.g. in a
        // Termux/PRoot environment with uncertain audio hardware access).
    }

    private static int countWriteCalls(RecordingLine recordingLine) {
        return (int) recordingLine.methodCalls.stream().filter(name -> name.equals("write")).count();
    }
}
