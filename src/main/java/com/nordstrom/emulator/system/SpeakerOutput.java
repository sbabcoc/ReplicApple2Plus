package com.nordstrom.emulator.system;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;

/**
 * Turns {@link SpeakerToggle}'s real-time toggle history into an actual
 * audio square wave -- the only way real Apple II software ever produces
 * sound: there is no frequency or volume register, only this one
 * flip-flop and the software-controlled timing between accesses to it
 * (see that class's own Javadoc). This class does not reinterpret that
 * timing after the fact; it samples {@link SpeakerToggle#isSpeakerHigh()}
 * live as CPU cycles tick, the same real-time relationship the real
 * hardware has between software timing and the physical speaker cone.
 * <p>
 * This is safe to do synchronously, unlike video: {@link ScanlineModes}
 * needs historical recording because {@code ScreenPanel}'s repaint is
 * deferred, asynchronous, and happens well after a whole batch of CPU
 * cycles has already run. This class's {@link #tick} runs inline, as
 * part of that same cycle-by-cycle progression -- there is no later,
 * asynchronous moment where the toggle's state could have gone stale by
 * the time it's read.
 * <p>
 * Ticked via {@link SystemClock#addCycleListener}, the same mechanism
 * already proven with {@link VideoScanner}, {@link ScanlineModes}, and
 * {@link PaddleTimers}. Samples accumulate into an internal buffer as
 * cycles tick; {@link #flush} writes the accumulated buffer to the
 * actual audio device and is meant to be called once per driving loop
 * iteration (e.g. once per {@code Apple2Plus} Timer tick, alongside its
 * existing {@code repaint()} call) -- not once per sample, which would
 * mean far more {@code SourceDataLine} write calls than necessary.
 * <p>
 * If no audio device is available (a real possibility this project
 * can't assume away, e.g. in a Termux/PRoot environment with uncertain
 * audio hardware access), this degrades to a silent no-op rather than
 * failing the whole emulator -- confirmed by catching
 * {@link LineUnavailableException} at construction, not left to surface
 * as an unhandled exception the first time {@link #tick} runs.
 */
public final class SpeakerOutput {

    // This project's own actual, real-wall-clock-time effective clock
    // rate -- NOT Sather's documented true ~1,022,727 Hz hardware rate.
    // Apple2Plus's own CYCLES_PER_TICK/FRAME_INTERVAL_MS (20,000 cycles
    // per real 20ms) deliberately approximates real Apple II speed
    // rather than matching it exactly (see that class's own Javadoc:
    // "real-time accuracy to that degree isn't this project's current
    // goal") -- meaning this emulator's cycles actually elapse, in real
    // wall-clock time, at 20,000/0.020 = 1,000,000 Hz, not 1,022,727 Hz.
    // Audio is the one subsystem where that ~2.2% difference actually
    // matters: unlike CPU execution or video, where nobody notices a
    // slightly-approximated frame rate, a SourceDataLine consumes
    // samples at a real, fixed rate regardless of what the emulator
    // thinks time is doing. Using the "true" hardware rate here meant
    // this class was generating about 2.2% fewer samples than real time
    // actually needed, every tick -- gradually starving the line's
    // buffer until it underran, which presented as a continuous
    // low-frequency hum rather than the intended square wave.
    private static final double CPU_HZ = 1_000_000;
    private static final float SAMPLE_RATE = 44_100f;
    private static final double CYCLES_PER_SAMPLE = CPU_HZ / SAMPLE_RATE;

    // 16-bit signed PCM, little-endian: the standard, universally-tested
    // format on every platform and audio driver -- switched from 8-bit
    // unsigned specifically because that far less common format was
    // suspected (though never proven) as the cause of a continuous
    // audible artifact that persisted even though this class's own
    // sample-generation logic was independently, directly confirmed
    // correct (byte-for-byte silent after the beep, verified by
    // injecting a recording proxy into a real, running instance during
    // an actual boot). These specific amplitude values are a
    // reasonable, comfortable volume choice for a modern output device,
    // not a hardware-derived fact -- real hardware has no "sample
    // value" at all, only an analog speaker cone position.
    private static final short SILENCE = 0;
    private static final short LOW = -8192;
    private static final short HIGH = 8192;

    private final SpeakerToggle speakerToggle;
    private final SourceDataLine line;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private double cycleAccumulator;
    private boolean lastSampledState;

    // Confirmed by direct measurement (not assumed): once the toggle has
    // gone untouched for a couple of full ticks, it stays untouched
    // indefinitely -- real audible tones toggle many times per 20ms
    // tick, so genuine silence is unambiguous at this threshold. Once
    // confirmed idle, samples emit the true SILENCE value instead of
    // whichever of LOW/HIGH the toggle happened to settle on -- the
    // actual bug (confirmed by direct diagnostic: exactly 192 toggles
    // for the boot beep, then a flat, confirmed zero every second
    // after, yet an audible tone persisted regardless) was feeding the
    // line a constant, off-center value (96, not the true 128 center)
    // forever. An earlier attempt fixed this by having flush() stop
    // writing to the line entirely once idle -- but that introduced a
    // new, worse problem: stopping and restarting a SourceDataLine
    // isn't instantaneous, and the boot beep is only ~105ms long (192
    // toggles at ~546 cycles apart), so the beep could finish before
    // the line's own wake-up latency caught up -- surfacing as
    // inconsistent triggering and a thump preceding the tone, both
    // symptoms of the restart transient rather than the intended
    // waveform. Feeding the line continuously, with the actually
    // correct silence value while idle, avoids both problems: no
    // wrong DC offset during silence, and no stop/restart transient
    // to wake up from.
    private static final int IDLE_TICKS_THRESHOLD = 2;
    private int ticksSinceLastToggle;

    /**
     * Builds an output driven by the same toggle the speaker hardware itself uses.
     *
     * @param speakerToggle the same speaker toggle wired into $C030-$C03F
     */
    public SpeakerOutput(SpeakerToggle speakerToggle) {
        this(speakerToggle, openLine());
    }

    /**
     * Package-visible for tests: injects a line directly (a real one, or
     * a test double) instead of trying to open a real OS audio device.
     * The public constructor is the only one real callers ever need;
     * this exists so {@code SpeakerOutputTest} can verify this class's
     * own sample-generation and lifecycle logic without depending on a
     * real, unpredictable audio device being present.
     *
     * @param speakerToggle the same speaker toggle wired into $C030-$C03F
     * @param line the line to use, or {@code null} to exercise the
     *             no-device code path
     */
    SpeakerOutput(SpeakerToggle speakerToggle, SourceDataLine line) {
        this.speakerToggle = speakerToggle;
        this.line = line;
    }

    // The line's buffer is also this class's audio latency. The emulation
    // loop hands audio over with a blocking write, so once the buffer is
    // full the emulator runs exactly as far ahead of what you hear as the
    // buffer holds -- a keypress's beep sounds that long after the screen
    // reacts. A quarter second was audibly laggy; a single tick (20ms)
    // would leave no room for the emulation thread to be late even once.
    // Four ticks tolerates a stall of roughly 60ms before the line runs
    // dry. This is a tuning judgment, not a measured optimum: raise it if
    // a slow or busy machine glitches, lower it if the delay is still
    // noticeable.
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int BUFFER_MILLIS = 80;
    private static final int BUFFER_SIZE_BYTES = (int) (SAMPLE_RATE * BUFFER_MILLIS / 1000) * BYTES_PER_SAMPLE;

    private static SourceDataLine openLine() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
            sourceDataLine.open(format, BUFFER_SIZE_BYTES);
            sourceDataLine.start();
            return sourceDataLine;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // No usable audio device -- run silently rather than fail
            // the whole emulator over a missing speaker.
            return null;
        }
    }

    /**
     * Advances by the cycles just elapsed, appending one audio sample to
     * the internal buffer for each sample boundary crossed. Each sample
     * reflects {@link SpeakerToggle#isSpeakerHigh()} at that instant --
     * unless the toggle has been confirmed idle for
     * {@link #IDLE_TICKS_THRESHOLD} ticks or more, in which case the
     * sample emits true silence instead. The idle state is re-checked
     * per sample, not once per tick: a toggle landing partway through
     * an otherwise-idle tick (exactly what resuming from idle looks
     * like) un-silences starting at that exact sample, not a full tick
     * later -- an earlier version checked this once at the tick's
     * start, which silenced the entire first tick of a new tone or beep
     * whenever it happened to start right after an idle period.
     *
     * @param cycles cycles elapsed since the last tick
     */
    public void tick(int cycles) {
        if (line == null) {
            return; // no device -- nothing to buffer
        }
        boolean toggledThisTick = false;
        cycleAccumulator += cycles;
        while (cycleAccumulator >= CYCLES_PER_SAMPLE) {
            cycleAccumulator -= CYCLES_PER_SAMPLE;
            boolean high = speakerToggle.isSpeakerHigh();
            if (high != lastSampledState) {
                // Reset immediately, within this same sample loop --
                // not deferred to the next tick. A toggle partway
                // through an otherwise-idle tick (exactly what resuming
                // from idle looks like) must un-silence starting at
                // that very sample, not wait a full tick to notice.
                toggledThisTick = true;
                ticksSinceLastToggle = 0;
                lastSampledState = high;
            }
            boolean confirmedIdle = ticksSinceLastToggle >= IDLE_TICKS_THRESHOLD;
            writeSample(confirmedIdle ? SILENCE : (high ? HIGH : LOW));
        }
        if (!toggledThisTick) {
            ticksSinceLastToggle++;
        }
    }

    /**
     * Appends one 16-bit signed, little-endian sample to the buffer --
     * low byte first, then high byte, matching this class's own
     * {@code AudioFormat(SAMPLE_RATE, 16, 1, true, false)}.
     */
    private void writeSample(short value) {
        buffer.write(value & 0xFF);
        buffer.write((value >> 8) & 0xFF);
    }

    /**
     * Whether a real audio device is open. When true, {@link #flush}
     * blocks once the device's buffer is full, which is what lets the
     * emulation loop use it as its clock; when false, {@code flush} is
     * a no-op and something else must pace the machine.
     *
     * @return true if audio is actually being played
     */
    public boolean hasDevice() {
        return line != null;
    }

    /**
     * Writes the accumulated buffer to the actual audio device and
     * clears it. Always writes, even during confirmed silence -- see
     * this class's own Javadoc for why continuous feeding, rather than
     * stopping and restarting the line, matters here. A no-op if no
     * audio device is available, or if nothing has accumulated since
     * the last call.
     * <p>
     * Blocks when the device's buffer is full, until the hardware has
     * consumed enough to accept the rest. That blocking is deliberate
     * and load-bearing: the emulation loop relies on it to hold the
     * machine to real time (see {@code EmulationLoop}). It must
     * therefore be called from the emulation thread, never the Swing
     * event thread.
     */
    public void flush() {
        if (line == null || buffer.size() == 0) {
            return;
        }
        byte[] samples = buffer.toByteArray();
        line.write(samples, 0, samples.length);
        buffer.reset();
    }

    /**
     * Drains, stops, and closes the actual audio device, releasing the
     * real OS/native audio resource this class opened. A no-op if no
     * device was ever available. Meant to be called once, on shutdown
     * -- not part of the normal tick/flush cycle. Never leaving this
     * unreleased matters even when {@code EXIT_ON_CLOSE} terminates the
     * JVM cleanly: an opened native audio line the process never
     * explicitly released is a real, avoidable gap regardless of
     * exactly how a given platform's audio subsystem behaves in that
     * situation.
     */
    public void close() {
        if (line == null) {
            return;
        }
        line.drain();
        line.stop();
        line.close();
    }
}
