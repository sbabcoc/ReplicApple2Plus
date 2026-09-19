package com.nordstrom.emulator.system;

/**
 * Computes which memory address the video circuitry is fetching at any
 * given point in the frame -- the specific piece floating-bus emulation
 * needs, not a full pixel renderer. Ticked via
 * {@link SystemClock#addCycleListener}, the same mechanism already
 * proven with {@link PaddleTimers}.
 * <p>
 * This is a faithful port of AppleWin's own {@code VideoGetScannerAddress}
 * (source/Video.cpp), which itself cites Jim Sather's "Understanding
 * the Apple IIe" (UTAIIe) hardware reference throughout -- not a
 * derivation of this project's own. Reproducing an existing, mature
 * emulator's verified bit-level logic directly is more trustworthy here
 * than re-deriving the same result independently, given how easy this
 * specific area is to get subtly wrong: a naive linear extension of the
 * visible-line addressing formula past line 191 would not reproduce the
 * real hardware's genuine 6-line counter stutter (explained below), and
 * would silently produce wrong addresses during vertical blanking
 * rather than failing loudly.
 * <p>
 * Real, independently-confirmed facts this reflects: 65 CPU cycles per
 * scanline (40 visible + 25 horizontal blanking), 262 scanlines per
 * frame (192 visible + 70 vertical blanking) -- but critically, the
 * real hardware's vertical counter is only 8 bits (256 states) and
 * reaches 262 scanlines by genuinely repeating ("stuttering") its own
 * last 6 states, not by counting linearly to 261. This class reproduces
 * that stutter exactly (the {@code nVState} preset-and-subtract logic
 * below), not an approximation of it. Mixed mode's real switching rule
 * -- text on the bottom scanlines regardless of the overall lores/hires
 * selection -- falls directly out of this same formula
 * ({@code v_4 && v_2}), rather than needing to be implemented
 * separately.
 * <p>
 * The well-known "long cycle" (one 65th-cycle per line stretched
 * slightly for NTSC colorburst phase alignment) is a real-time,
 * wall-clock detail that does not change the CPU's own cycle COUNT --
 * confirmed directly ("the CPU itself couldn't care less what the
 * clock frequency is") -- so it's irrelevant to this class, the same
 * reasoning that already justified {@link SystemClock} excluding
 * real-time pacing entirely. Composite-signal-only timing (colorburst
 * and sync pulse generation) is likewise omitted -- this class answers
 * "what address," never "what does the analog signal look like."
 * <p>
 * Deliberately still targets the Apple II+'s own, original video
 * generator specifically (not the IIe's enhanced one, which this
 * project doesn't model) -- there is no 80-column/80STORE switch here,
 * since the II+ has none.
 */
public final class VideoScanner {

    private static final int H_CLOCKS = 65;             // clocks per horizontal scan (including HBL)
    private static final int H_CLOCK_0_STATE = 0x18;     // H[543210] = 011000
    private static final int H_PE_CLOCK = 40;            // clock when HPE (horizontal preset enable) goes low
    private static final int H_PRESET_CLOCK = 41;        // clock when H state presets

    private static final int SCAN_LINES = 262;           // total scan lines including VBL (NTSC)
    private static final int V_LINE_0_STATE = 0x100;     // V[543210CBA] = 100000000
    private static final int V_PRESET_LINE = 256;        // line when V state presets

    private static final int CYCLES_PER_FRAME = H_CLOCKS * SCAN_LINES; // 17030

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
     * The memory address the video circuitry is fetching right now --
     * always a real address, including during horizontal blanking,
     * vertical blanking, and mixed mode, matching real hardware, which
     * never actually stops addressing memory just because nothing is
     * currently visible.
     *
     * @return the current video scan address
     */
    public int currentAddress() {
        // AppleWin's own nCycles reference frame is phase-shifted 25 cycles
        // relative to this class's "frameCycle 0 = start of the visible frame"
        // convention -- confirmed empirically: without this offset, frameCycle 0
        // does not land on column 0 of row 0 at all. Adding it here keeps this
        // class's own public contract (frameCycle 0 = first visible byte) while
        // still using AppleWin's formula exactly as published internally.
        int cycles = (int) ((frameCycle + 25) % CYCLES_PER_FRAME);

        // horizontal scanning state
        int hClock = (cycles + H_PE_CLOCK) % H_CLOCKS;
        int hState = H_CLOCK_0_STATE + hClock;
        if (hClock >= H_PRESET_CLOCK) {
            hState -= 1;
        }
        int h0 = (hState >> 0) & 1;
        int h1 = (hState >> 1) & 1;
        int h2 = (hState >> 2) & 1;
        int h3 = (hState >> 3) & 1;
        int h4 = (hState >> 4) & 1;
        int h5 = (hState >> 5) & 1;

        // vertical scanning state (UTAIIe:3-15,T3.2) -- the stutter lives here
        int vLine = cycles / H_CLOCKS;
        int vState = V_LINE_0_STATE + vLine;
        if (vLine >= V_PRESET_LINE) {
            vState -= SCAN_LINES;
        }
        int vA = (vState >> 0) & 1;
        int vB = (vState >> 1) & 1;
        int vC = (vState >> 2) & 1;
        int v0 = (vState >> 3) & 1;
        int v1 = (vState >> 4) & 1;
        int v2 = (vState >> 5) & 1;
        int v3 = (vState >> 6) & 1;
        int v4 = (vState >> 7) & 1;

        boolean hires = videoSoftSwitches.isHires() && !videoSoftSwitches.isText();
        boolean page2 = videoSoftSwitches.isPage2();

        // mixed mode: text on the bottom scanlines regardless of hires/lores selection (UTAIIe:5-7,P3)
        if (hires && videoSoftSwitches.isMixed() && v4 != 0 && v2 != 0) {
            hires = false;
        }

        int addend0 = 0x0D;
        int addend1 = (h5 << 2) | (h4 << 1) | h3;
        int addend2 = (v4 << 3) | (v3 << 2) | (v4 << 1) | v3;
        int sum = (addend0 + addend1 + addend2) & 0x0F;

        int addressH = 0;
        addressH |= h0 << 0;
        addressH |= h1 << 1;
        addressH |= h2 << 2;
        addressH |= sum << 3;
        if (!hires && h5 == 0 && (h4 == 0 || h3 == 0)) {
            // real Apple II+ (not IIe) HBL address-bit quirk (UTAIIe:8-10,F8.5)
            addressH |= 1 << 12;
        }

        int addressV = 0;
        addressV |= v0 << 7;
        addressV |= v1 << 8;
        addressV |= v2 << 9;

        int addressP = 0;
        if (hires) {
            addressV |= vA << 10;
            addressV |= vB << 11;
            addressV |= vC << 12;
            addressP |= (page2 ? 0 : 1) << 13;
            addressP |= (page2 ? 1 : 0) << 14;
        } else {
            addressP |= (page2 ? 0 : 1) << 10;
            addressP |= (page2 ? 1 : 0) << 11;
        }

        return addressP | addressV | addressH;
    }
}
