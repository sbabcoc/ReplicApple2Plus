package com.nordstrom.emulator.system;

/**
 * Computes which memory address the video circuitry is fetching at any
 * given point in the frame -- the specific piece floating-bus emulation
 * needs, not a full pixel renderer. Ticked via
 * {@link SystemClock#addCycleListener}, the same mechanism already
 * proven with {@link PaddleTimers}.
 * <p>
 * Real, independently-confirmed timing: 65 CPU cycles per scanline (40
 * visible + 25 horizontal blanking), 262 scanlines per frame (192
 * visible + 70 vertical blanking) -- 17030 cycles per frame total. The
 * well-known "long cycle" (one 65th-cycle per line stretched slightly
 * for NTSC colorburst phase alignment) is a real-time, wall-clock
 * detail that does not change the CPU's own cycle COUNT -- confirmed
 * directly ("the CPU itself couldn't care less what the clock frequency
 * is") -- so it's irrelevant to this class, the same reasoning that
 * already justified {@link SystemClock} excluding real-time pacing
 * entirely.
 * <p>
 * Row-to-address formulas, both confirmed against a real, detailed
 * row-address listing:
 * <pre>
 *   text/lores: $400 + $80*(row%8) + $28*(row/8)
 *   hi-res:     $2000 + $400*(row%8) + $80*((row/8)%8) + $28*(row/64)
 * </pre>
 * Text and lo-res share identical addressing (confirmed: both live in
 * the same 1K page) -- what differs is only how the video generator
 * interprets the fetched byte for display, which floating-bus purposes
 * never care about.
 * <p>
 * Deliberately only covers the VISIBLE portion of each scanline in a
 * single, non-mixed mode. Horizontal blanking, vertical blanking, and
 * mixed mode are all real, named gaps here, not silent guesses:
 * <ul>
 *   <li>Text/lores mode is documented to fetch different, off-screen
 *       addresses during horizontal blanking specifically for DRAM
 *       refresh (hires mode does not do this at all) -- this project
 *       doesn't yet have a verified formula for exactly which addresses
 *       those are.</li>
 *   <li>Vertical blanking's fetch behavior isn't modeled at all yet.</li>
 *   <li>Mixed mode shows text on the bottom scanlines regardless of the
 *       overall lores/hires selection -- a real, well-documented
 *       behavior, but one this class defers rather than implementing
 *       without the same verification rigor given to the two formulas
 *       above.</li>
 * </ul>
 * Throwing for these, rather than guessing, matches this project's
 * standing rule about unverified hardware behavior.
 */
public final class VideoScanner {

    private static final int CYCLES_PER_SCANLINE = 65;
    private static final int VISIBLE_CYCLES_PER_SCANLINE = 40;
    private static final int SCANLINES_PER_FRAME = 262;
    private static final int VISIBLE_SCANLINES = 192;
    private static final int CYCLES_PER_FRAME = CYCLES_PER_SCANLINE * SCANLINES_PER_FRAME; // 17030

    private static final String HBLANK_GAP =
        "Horizontal blanking's video-scan address is not yet modeled "
        + "(text/lores fetches different, off-screen addresses here for DRAM refresh; this project "
        + "doesn't yet have a verified formula for exactly which ones)";
    private static final String VBLANK_GAP = "Vertical blanking's video-scan address is not yet modeled";
    private static final String MIXED_GAP =
        "Mixed mode's per-scanline text/graphics address switching is not yet modeled";

    private final VideoSoftSwitches videoSoftSwitches;
    private long frameCycle;

    /**
     * @param videoSoftSwitches the same video mode switches wired into $C050-$C05F
     */
    public VideoScanner(VideoSoftSwitches videoSoftSwitches) {
        this.videoSoftSwitches = videoSoftSwitches;
    }

    /**
     * Advances the frame position by the cycles just elapsed, wrapping
     * at the end of each 17030-cycle frame.
     *
     * @param cycles cycles elapsed since the last tick
     */
    public void tick(int cycles) {
        frameCycle = (frameCycle + cycles) % CYCLES_PER_FRAME;
    }

    /**
     * The memory address the video circuitry is fetching right now.
     *
     * @return the current video scan address
     * @throws UnsupportedOperationException during horizontal blanking,
     *         vertical blanking, or while mixed mode is active -- see
     *         this class's own Javadoc for why each is a named gap
     */
    public int currentAddress() {
        int line = (int) (frameCycle / CYCLES_PER_SCANLINE);
        int cycleInLine = (int) (frameCycle % CYCLES_PER_SCANLINE);

        if (line >= VISIBLE_SCANLINES) {
            throw new UnsupportedOperationException(VBLANK_GAP);
        }
        if (cycleInLine >= VISIBLE_CYCLES_PER_SCANLINE) {
            throw new UnsupportedOperationException(HBLANK_GAP);
        }
        if (videoSoftSwitches.isMixed()) {
            throw new UnsupportedOperationException(MIXED_GAP);
        }

        boolean hiresAddressing = !videoSoftSwitches.isText() && videoSoftSwitches.isHires();
        int rowBase = hiresAddressing
            ? 0x2000 + 0x400 * (line % 8) + 0x80 * ((line / 8) % 8) + 0x28 * (line / 64)
            : 0x400 + 0x80 * (line % 8) + 0x28 * (line / 8);
        int pageOffset = videoSoftSwitches.isPage2() ? (hiresAddressing ? 0x2000 : 0x400) : 0;

        return rowBase + pageOffset + cycleInLine;
    }
}
