package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.system.PowerOnRam;
import com.nordstrom.emulator.system.SlotCard;

import java.util.Properties;
import java.util.Set;

/**
 * The Videx VideoTerm 80-column card, firmware 2.4. Must be in slot 3 --
 * the firmware is hard-coded for it. Specified in HARDWARE-REFERENCE.md
 * section 3; this class models the card's hardware, and the real firmware
 * does the rest.
 * <p>
 * Address map, for slot 3:
 * <ul>
 *   <li><b>{@code $C0B0}-{@code $C0BF}</b> (device select): every access,
 *       read or write, maps VRAM page {@code (offset >> 2) & 3} into the
 *       {@code $CC00} window. Writes to an even offset select a CRTC
 *       register; writes to an odd offset load the selected register. Reads
 *       at an odd offset return the readable registers (R14-R17).</li>
 *   <li><b>{@code $C300}-{@code $C3FF}</b>: the firmware's last 256 bytes
 *       (image offset {@code $300}), holding the slot entry points and the
 *       Pascal 1.1 protocol bytes. Touching this page also claims the
 *       shared expansion-ROM space, via the bus's latch.</li>
 *   <li><b>{@code $C800}-{@code $CBFF}</b>: the 1K firmware.</li>
 *   <li><b>{@code $CC00}-{@code $CDFF}</b>: a 512-byte window onto the 2K
 *       VRAM, at the page last selected through the device-select range.</li>
 *   <li><b>{@code $CE00}-{@code $CFFF}</b>: not used by the card (reads 0).</li>
 * </ul>
 * The CRTC is an MC6845-compatible part with 18 registers: R0-R15 written
 * by the firmware's setup, R14-R17 readable (R16/R17 being the light pen,
 * which isn't modeled and reads 0).
 * <p>
 * Configuration: {@code display} sets the initial display mode,
 * {@code switched} (the default) or {@code separate}; the application can
 * change it while running. Switched models the Soft Video Switch -- one
 * screen, showing 80 columns when annunciator 0 is on and the machine is in
 * text mode. Separate gives the 80-column output its own window and leaves
 * the main window always showing the Apple's video.
 */
public final class VideoTerm implements SlotCard {

    /** How the 80-column output reaches the screen. */
    public enum Display {
        /** One window; the Soft Video Switch picks 80 columns when AN0 is on and graphics is off. */
        SWITCHED,
        /** A second window always shows the 80-column output. */
        SEPARATE
    }

    static final int VRAM_SIZE = 2048;
    private static final int PAGE_SIZE = 512;
    private static final int SLOT_PAGE_OFFSET = 0x300;
    private static final int VRAM_WINDOW_START = 0x400; // $CC00, as an offset within $C800-$CFFF
    private static final int VRAM_WINDOW_END = VRAM_WINDOW_START + PAGE_SIZE;

    private byte[] firmware;
    private byte[] vram;
    private final int[] registers = new int[18];
    private int selectedRegister;
    private int vramPage;
    private volatile Display display = Display.SWITCHED; // read by the paint thread, set from the UI

    @Override
    public String getShortName() {
        return "videoterm";
    }

    @Override
    public Set<String> getSupportedParameters() {
        return Set.of("display");
    }

    @Override
    public void configure(Properties props) {
        String mode = props.getProperty("display", "switched").trim();
        display = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "switched" -> Display.SWITCHED;
            case "separate" -> Display.SEPARATE;
            default -> throw new IllegalArgumentException(
                "videoterm: display must be 'switched' or 'separate', not '" + mode + "'");
        };
        firmware = VideoTermRoms.firmware();
        VideoTermRoms.charset(); // fail at startup, not first paint, if the character ROM is missing or wrong
        vram = PowerOnRam.allocate(VRAM_SIZE); // static RAM, also indeterminate at power-on
    }

    /** @return how this card's output should be displayed */
    public Display display() {
        return display;
    }

    /**
     * Changes how this card's output is displayed -- the display mode is a
     * choice about the emulator's windows, not card state software can see.
     *
     * @param display the new display mode
     */
    public void setDisplay(Display display) {
        this.display = display;
    }

    @Override
    public int readIoSwitch(int offset) {
        selectPage(offset);
        if ((offset & 1) == 0) {
            return 0;
        }
        return switch (selectedRegister) {
            case 14, 15 -> registers[selectedRegister];
            default -> 0; // write-only registers, and the unmodeled light pen (R16/R17)
        };
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        selectPage(offset);
        if ((offset & 1) == 0) {
            selectedRegister = value & 0x1F;
        } else if (selectedRegister < 16) {
            registers[selectedRegister] = value & 0xFF;
        }
    }

    private void selectPage(int offset) {
        vramPage = (offset >> 2) & 3;
    }

    @Override
    public int readRom(int offset) {
        return firmware[SLOT_PAGE_OFFSET + offset] & 0xFF;
    }

    @Override
    public boolean wantsExpansionRom() {
        return true;
    }

    @Override
    public int readExpansionRom(int offset) {
        if (offset < VRAM_WINDOW_START) {
            return firmware[offset] & 0xFF;
        }
        if (offset < VRAM_WINDOW_END) {
            return vram[vramPage * PAGE_SIZE + (offset - VRAM_WINDOW_START)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void writeExpansionRom(int offset, int value) {
        if (offset >= VRAM_WINDOW_START && offset < VRAM_WINDOW_END) {
            vram[vramPage * PAGE_SIZE + (offset - VRAM_WINDOW_START)] = (byte) value;
        }
    }

    /**
     * @param n a CRTC register number, 0-17
     * @return its current value (R16/R17, the unmodeled light pen, read 0)
     */
    public int register(int n) {
        return n < 16 ? registers[n] : 0;
    }

    /**
     * @param address a VRAM address; wrapped to the 2K VRAM
     * @return the byte stored there
     */
    public int vram(int address) {
        return vram[address & (VRAM_SIZE - 1)] & 0xFF;
    }

    /** Package-visible for tests: which VRAM page the $CC00 window currently shows. */
    int vramPage() {
        return vramPage;
    }
}
