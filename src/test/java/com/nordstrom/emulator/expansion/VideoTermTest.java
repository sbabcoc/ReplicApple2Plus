package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoTermTest {

    private static VideoTerm configured(String display) {
        VideoTerm card = new VideoTerm();
        Properties props = new Properties();
        if (display != null) {
            props.setProperty("display", display);
        }
        card.configure(props);
        return card;
    }

    @Test
    void theSlotPageIsTheFirmwaresLastPage() {
        VideoTerm card = configured(null);
        // $C300: BIT $FFCB, the standard slot-ROM entry; $C305/$C307/$C30B/$C30C: the Pascal 1.1 protocol IDs.
        assertEquals(0x2C, card.readRom(0x00));
        assertEquals(0xCB, card.readRom(0x01));
        assertEquals(0xFF, card.readRom(0x02));
        assertEquals(0x38, card.readRom(0x05));
        assertEquals(0x18, card.readRom(0x07));
        assertEquals(0x01, card.readRom(0x0B));
        assertEquals(0x82, card.readRom(0x0C));
        for (int i = 0; i < 0x100; i++) {
            assertEquals(card.readExpansionRom(0x300 + i), card.readRom(i), "$C3xx must mirror $CBxx at offset " + i);
        }
    }

    @Test
    void theExpansionSpaceStartsWithTheFirmware() {
        VideoTerm card = configured(null);
        // $C800: LDA $077B -- the start of SETUP.
        assertEquals(0xAD, card.readExpansionRom(0x000));
        assertEquals(0x7B, card.readExpansionRom(0x001));
        assertEquals(0x07, card.readExpansionRom(0x002));
        assertTrue(card.wantsExpansionRom());
    }

    @Test
    void crtcRegistersAreLoadedThroughIndexThenData() {
        VideoTerm card = configured(null);
        card.writeIoSwitch(0x0, 1);   // $C0B0: select R1
        card.writeIoSwitch(0x1, 80);  // $C0B1: R1 = 80
        card.writeIoSwitch(0x0, 14);
        card.writeIoSwitch(0x1, 0x02);
        card.writeIoSwitch(0x0, 15);
        card.writeIoSwitch(0x1, 0x31);
        assertEquals(80, card.register(1));
        card.writeIoSwitch(0x0, 14);
        assertEquals(0x02, card.readIoSwitch(0x1), "the cursor registers are readable");
        card.writeIoSwitch(0x0, 15);
        assertEquals(0x31, card.readIoSwitch(0x1));
        card.writeIoSwitch(0x0, 1);
        assertEquals(0, card.readIoSwitch(0x1), "R1 is write-only");
    }

    @Test
    void anyDeviceSelectAccessPicksTheVramPageItAddresses() {
        VideoTerm card = configured(null);
        for (int page = 0; page < 4; page++) {
            card.readIoSwitch(page * 4);                 // a read, as the firmware's LDA $C0B0,X does
            card.writeExpansionRom(0x400, 0x10 + page);  // $CC00
            card.writeExpansionRom(0x5FF, 0x20 + page);  // $CDFF
        }
        for (int page = 0; page < 4; page++) {
            assertEquals(0x10 + page, card.vram(page * 512), "start of page " + page);
            assertEquals(0x20 + page, card.vram(page * 512 + 511), "end of page " + page);
        }
        card.writeIoSwitch(0x9, 0); // a write to $C0B9 (bits 2-3 = 10) also selects page 2
        assertEquals(2, card.vramPage());
        assertEquals(0x12, card.readExpansionRom(0x400), "the window reads the selected page");
    }

    @Test
    void theUnusedTopOfTheExpansionSpaceReadsZeroAndIgnoresWrites() {
        VideoTerm card = configured(null);
        card.writeExpansionRom(0x600, 0x55); // $CE00
        assertEquals(0, card.readExpansionRom(0x600));
        card.writeExpansionRom(0x000, 0x55); // the firmware is ROM
        assertEquals(0xAD, card.readExpansionRom(0x000));
    }

    @Test
    void displayModeComesFromConfiguration() {
        assertEquals(VideoTerm.Display.SWITCHED, configured(null).display());
        assertEquals(VideoTerm.Display.SWITCHED, configured("switched").display());
        assertEquals(VideoTerm.Display.SEPARATE, configured("Separate").display());
        assertThrows(IllegalArgumentException.class, () -> configured("both"));
    }
}
