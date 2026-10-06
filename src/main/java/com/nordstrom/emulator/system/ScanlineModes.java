package com.nordstrom.emulator.system;

import java.util.Arrays;

/**
 * Records which video source (text/lores/hires) and which page applied
 * to each of the 192 visible scan lines, as CPU cycles actually tick --
 * not a snapshot of "whatever the live switches say right now" at
 * whatever later moment a renderer happens to run.
 * <p>
 * This exists because of a real gap in this project's own rendering
 * timing: {@code Apple2Plus}'s Swing repaint timer runs a whole batch of
 * CPU cycles (frequently spanning multiple complete
 * {@link VideoScanner#CYCLES_PER_FRAME}-cycle video frames) before ever
 * calling {@code repaint()}. A renderer that simply asked
 * {@link VideoSoftSwitches} "what mode is active" at paint time would
 * only ever see whatever the LAST thing the CPU did left it as --
 * losing any mid-frame mode changes entirely. Real Apple II software
 * exploits exactly this: timing precise reads of $C050/$C051 against
 * the floating bus (each read also flips the TEXT switch as a side
 * effect) to split the screen at an arbitrary scan line, a real,
 * documented technique (Bob Bishop's "floating bus" demo program,
 * Softalk, October 1982, included as a Virtual ][ example script) --
 * not a hypothetical concern.
 * <p>
 * Deliberately does NOT snapshot pixel/memory content, only the mode
 * (source + page) active at each line -- content doesn't need the same
 * historical treatment, since nothing in practice relies on memory
 * bytes changing mid-repaint the way {@link TextScreenRenderer} already
 * assumes for text (see that class's own Javadoc). A renderer reads the
 * actual bytes live at paint time, using whichever mode was recorded
 * for each line.
 * <p>
 * Ticked via {@link SystemClock#addCycleListener}, the same mechanism
 * already proven with {@link VideoScanner} and {@link PaddleTimers} --
 * and, like those, holds no reference back to a clock, only advancing
 * by whatever cycle count it's told just elapsed.
 * <p>
 * Double-buffered: {@link #tick} writes into the in-progress frame's
 * array; {@link #completedFrame()} returns the last FULLY finished
 * frame, safe for a renderer to read at any time without tearing
 * against an in-progress recording.
 */
public final class ScanlineModes {

    /** Visible scan lines per frame. */
    public static final int LINES = 192;

    /** Which source produces a given scan line's pixels. */
    public enum Source {
        /** 40x24 text, via {@link TextScreenRenderer}. */
        TEXT,
        /** 40x48 low-resolution color blocks. */
        LORES,
        /** 280x192 high-resolution graphics. */
        HIRES
    }

    /**
     * One scan line's recorded mode: which source, and which page it reads from.
     *
     * @param source which renderer produces this line's pixels
     * @param page2 which of the two pages that source reads from
     */
    public record LineMode(Source source, boolean page2) {}

    private static final LineMode DEFAULT_LINE_MODE = new LineMode(Source.TEXT, false);

    private final VideoSoftSwitches videoSoftSwitches;

    private LineMode[] recording = freshFrame();
    // Volatile: written by the emulation thread when a frame finishes,
    // read by the Swing paint thread. Every entry is always non-null and
    // LineMode is an immutable record, so publishing the reference is all
    // the synchronization a reader needs.
    private volatile LineMode[] completed = freshFrame();
    private int frameCycle;
    private int lastRecordedLine = -1;

    /**
     * Builds a recorder driven by the same switches the video hardware itself uses.
     *
     * @param videoSoftSwitches the same video mode switches wired into $C050-$C05F
     */
    public ScanlineModes(VideoSoftSwitches videoSoftSwitches) {
        this.videoSoftSwitches = videoSoftSwitches;
    }

    private static LineMode[] freshFrame() {
        LineMode[] frame = new LineMode[LINES];
        Arrays.fill(frame, DEFAULT_LINE_MODE);
        return frame;
    }

    /**
     * Advances by the cycles just elapsed, recording the mode active at
     * the start of each newly-entered visible scan line, and swapping
     * to a fresh recording buffer whenever a new frame begins.
     * <p>
     * Advances in chunks no larger than one scan line's own cycle width
     * ({@link VideoScanner#H_CLOCKS}), specifically so a single large
     * {@code cycles} value (which shouldn't normally occur -- a 6502
     * instruction is always far fewer cycles than one scan line -- but
     * isn't assumed away either) can never skip over a scan-line
     * boundary without this method checking it.
     *
     * @param cycles cycles elapsed since the last tick
     */
    public void tick(int cycles) {
        int remaining = cycles;
        while (remaining > 0) {
            int step = Math.min(remaining, VideoScanner.H_CLOCKS);
            frameCycle = (frameCycle + step) % VideoScanner.CYCLES_PER_FRAME;
            remaining -= step;

            // Deliberately the SAME phase-shifted cycle space visibleScanLine()
            // and isMixedModeTextLine() use, not the raw frameCycle -- the +25
            // shift means this value wraps to "line 0" a full 25 cycles BEFORE
            // frameCycle itself reaches CYCLES_PER_FRAME. An earlier version of
            // this method detected the frame boundary from raw frameCycle
            // instead, which meant the last 25 cycles of every frame recorded
            // into line 0 of the buffer already destined to be published as
            // that frame's own line 0 -- silently corrupting it just before
            // publication. Detecting the wrap from the derived line number
            // itself (below) instead of a separately-tracked raw counter
            // avoids this by construction: there's only one source of truth
            // for "what line is this," so there's nothing left to disagree.
            int cyclesAt = (frameCycle + 25) % VideoScanner.CYCLES_PER_FRAME;
            int line = VideoScanner.visibleScanLine(cyclesAt);

            if (line >= 0 && line != lastRecordedLine) {
                if (line < lastRecordedLine) {
                    // line number went backwards -- the only way that happens
                    // is wrapping into a new frame (lastRecordedLine starts at
                    // -1, so the very first-ever recording, line >= 0, never
                    // satisfies this)
                    completed = recording;
                    recording = freshFrame();
                }
                recording[line] = currentLineMode(cyclesAt);
                lastRecordedLine = line;
            }
        }
    }

    private LineMode currentLineMode(int cyclesAt) {
        boolean page2 = videoSoftSwitches.isPage2();
        if (videoSoftSwitches.isText()) {
            return new LineMode(Source.TEXT, page2);
        }
        boolean hires = videoSoftSwitches.isHires();
        // Mixed mode shows its bottom four rows as text over lo-res and hi-res
        // alike. (VideoScanner's address logic tests hires alone because only
        // hi-res fetches from different addresses on those lines; what's
        // displayed there is text either way.)
        if (videoSoftSwitches.isMixed() && VideoScanner.isMixedModeTextLine(cyclesAt)) {
            return new LineMode(Source.TEXT, page2);
        }
        return new LineMode(hires ? Source.HIRES : Source.LORES, page2);
    }

    /**
     * The last fully-completed frame's per-line mode data -- safe to
     * read at any time, never torn against an in-progress recording.
     *
     * @return an array of {@link #LINES} entries, index 0 = top of screen
     */
    public LineMode[] completedFrame() {
        return completed;
    }
}
