package com.nordstrom.emulator.cpu;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.ScanlineModes;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the actual disassembled machine code from Bob Bishop's "floating
 * bus" screen-split demo (Softalk, October 1982, included as a Virtual
 * ][ example script) against the real CPU, {@link SystemClock}, and
 * {@link MotherboardBus} together, and confirms {@link ScanlineModes}
 * correctly records the resulting mid-frame TEXT/LORES/TEXT split --
 * the strongest available confirmation that {@code VideoSoftSwitches}'
 * real floating-bus read (not the fixed 0 it used to return) and
 * {@code ScanlineModes}' per-scan-line recording genuinely work
 * together, not just in isolation.
 * <p>
 * Same approach as {@link PaddleTimersPreadIntegrationTest}: real,
 * historical bytes poked directly into memory and run to completion via
 * a direct {@code pc} jump, not DOS or Applesoft involved at all -- this
 * lives in the {@code cpu} package for the same reason that class does,
 * direct access to {@link Cpu6502}'s own package-private {@code pc}.
 * The lo-res screen data this routine's own polling depends on is poked
 * directly too, standing in for what the real program's own BASIC
 * {@code FOR} loop (and its preceding {@code CALL -936} clear-screen)
 * would have produced -- confirmed, when this test was first written,
 * to reproduce the exact same real-hardware infinite loop this
 * shortcut sidesteps if the floating-bus fix isn't in place.
 */
class BishopScreenSplitIntegrationTest {

    // Bob Bishop's own machine code, POKEd verbatim by the original BASIC
    // program at decimal addresses 768-798 ($0300-$031E). Disassembly and
    // full explanation of what each instruction does: HARDWARE-REFERENCE.md
    // is not the right place for this (that's Videx/Saturn hardware, not
    // this) -- see this project's own commit history / PR description for
    // this test instead.
    private static final int[] BISHOP_ROUTINE = {
        0x8D, 0x52, 0xC0,       // $0300: STA $C052        (MIXED off)
        0xA9, 0xE0,             // $0303: LDA #$E0
        0xA2, 0x04,             // $0305: LDX #$04
        0xCD, 0x51, 0xC0,       // $0307: CMP $C051        (poll floating bus, side effect: TEXT on)
        0xD0, 0xF9,             // $030A: BNE $0305
        0xCA,                   // $030C: DEX
        0xD0, 0xF8,             // $030D: BNE $0307
        0xA9, 0xA0,             // $030F: LDA #$A0
        0xA2, 0x04,             // $0311: LDX #$04
        0xCD, 0x50, 0xC0,       // $0313: CMP $C050        (poll floating bus, side effect: TEXT off)
        0xD0, 0xF9,             // $0316: BNE $0311
        0xCA,                   // $0318: DEX
        0xD0, 0xF8,             // $0319: BNE $0313
        0x8D, 0x51, 0xC0,       // $031B: STA $C051        (TEXT back on)
        0x60                    // $031E: RTS
    };

    private static final int ROUTINE_START = 0x300;

    @Test
    void recordsTheExpectedTextLoResTextSplit() {
        SlotCard[] slots = new SlotCard[8];
        try (MotherboardBus bus = new MotherboardBus(slots)) {
            for (int i = 0; i < BISHOP_ROUTINE.length; i++) {
                bus.write(ROUTINE_START + i, BISHOP_ROUTINE[i]);
            }
            pokeStandInLoResScreen(bus);
            bus.write(0xC051, 0); // TEXT on -- matches the real machine already being in Applesoft's text mode

            Cpu6502 cpu = new Cpu6502(bus, 0xFFFC); // reset vector irrelevant -- overridden below
            cpu.pc = ROUTINE_START;
            SystemClock clock = new SystemClock(cpu);
            clock.addCycleListener(bus.videoScanner()::tick);
            clock.addCycleListener(bus.scanlineModes()::tick);

            boolean returned = false;
            for (int i = 0; i < 200_000; i++) {
                int opcode = bus.read(cpu.pc);
                clock.step();
                if (opcode == 0x60) { // just executed RTS
                    returned = true;
                    break;
                }
            }
            if (!returned) {
                fail("Routine never returned -- floating-bus polling loop likely never saw its target byte "
                    + "(if VideoSoftSwitches.read() has regressed to a fixed 0, this is exactly the symptom)");
            }

            // The routine finishes well within a single 17030-cycle frame, so
            // the frame containing the split isn't published to
            // completedFrame() (only a FULLY finished frame is) until a frame
            // boundary is actually crossed. Scan forward in small steps rather
            // than guessing a fixed wait -- robust regardless of exactly where
            // within the frame the routine happened to run.
            ScanlineModes.LineMode[] frame = null;
            for (int checkpoint = 0; checkpoint < 40 && frame == null; checkpoint++) {
                for (int i = 0; i < 1000; i++) {
                    clock.step();
                }
                ScanlineModes.LineMode[] snapshot = bus.scanlineModes().completedFrame();
                for (ScanlineModes.LineMode m : snapshot) {
                    if (m.source() != ScanlineModes.Source.TEXT) {
                        frame = snapshot;
                        break;
                    }
                }
            }
            if (frame == null) {
                fail("No non-TEXT line ever appeared in completedFrame() -- the split was never recorded");
            }

            assertEquals(ScanlineModes.Source.TEXT, frame[0].source(), "line 0 should be TEXT (top of screen)");
            assertEquals(ScanlineModes.Source.TEXT, frame[191].source(), "line 191 should be TEXT (bottom of screen)");

            int firstGraphicsLine = -1;
            int backToTextLine = -1;
            for (int line = 1; line < ScanlineModes.LINES; line++) {
                if (firstGraphicsLine < 0 && frame[line].source() != ScanlineModes.Source.TEXT) {
                    firstGraphicsLine = line;
                }
                if (firstGraphicsLine >= 0 && backToTextLine < 0 && frame[line].source() == ScanlineModes.Source.TEXT) {
                    backToTextLine = line;
                }
            }
            assertTrue(firstGraphicsLine > 0 && firstGraphicsLine < 191,
                "expected a transition into graphics somewhere in the middle of the screen, got line " + firstGraphicsLine);
            assertTrue(backToTextLine > firstGraphicsLine && backToTextLine < 191,
                "expected a transition back to text before the bottom of the screen, got line " + backToTextLine);
            assertEquals(ScanlineModes.Source.LORES, frame[firstGraphicsLine].source(),
                "the graphics region should be LORES, not HIRES -- this routine never touches the HIRES switch");
        }
    }

    /**
     * Stands in for what the real BASIC program's {@code CALL -936}
     * (clear screen) followed by its {@code FOR K = 0 TO 39} lo-res fill
     * loop would have left in memory: mostly $A0 (the cleared-screen
     * space character, which this routine's second polling loop
     * specifically depends on finding), with one row set to $E0 (the
     * first loop's own target byte) standing in for the real loop's
     * {@code VLIN} output, which would have produced some $E0 bytes
     * incidentally as part of cycling through all 16 lo-res colors.
     */
    private static void pokeStandInLoResScreen(MotherboardBus bus) {
        for (int addr = 0x400; addr <= 0x7FF; addr++) {
            bus.write(addr, 0xA0);
        }
        for (int col = 0; col < 40; col++) {
            bus.write(0x5A8 + col, 0xE0); // row 11 -- must include $E0, this routine's first polling target
        }
    }
}
