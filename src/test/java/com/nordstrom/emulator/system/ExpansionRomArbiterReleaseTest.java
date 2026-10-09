package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * $CFFF releases every latched expansion ROM at once -- including when more
 * than one card is latched, which is exactly the state it exists to resolve.
 * Real firmware relies on it: the VideoTerm's touches $CFFF on entry to
 * release whichever card was selected before it.
 */
class ExpansionRomArbiterReleaseTest {

    /** A card with a slot page and a 2K expansion ROM full of one byte value. */
    static final class RomCard implements SlotCard {
        final int fill;

        RomCard(int fill) {
            this.fill = fill;
        }

        @Override
        public void configure(Properties props) {
        }

        @Override
        public int readIoSwitch(int offset) {
            return 0;
        }

        @Override
        public void writeIoSwitch(int offset, int value) {
        }

        @Override
        public int readRom(int offset) {
            return fill;
        }

        @Override
        public boolean wantsExpansionRom() {
            return true;
        }

        @Override
        public int readExpansionRom(int offset) {
            return fill;
        }
    }

    private static MotherboardBus twoCards() {
        SlotCard[] slots = new SlotCard[8];
        slots[2] = new RomCard(0x22);
        slots[3] = new RomCard(0x33);
        return MotherboardBus.withoutAudio(slots);
    }

    @Test
    void cfffReleasesEveryLatchEvenWhenSeveralAreSet() {
        try (MotherboardBus bus = twoCards()) {
            bus.read(0xC200); // latches slot 2
            bus.read(0xC300); // latches slot 3 too: two cards selected
            assertDoesNotThrow(() -> bus.read(0xCFFF), "the documented way out of this state");
            bus.read(0xC300); // now only slot 3
            assertEquals(0x33, bus.read(0xC800));
        }
    }

    @Test
    void anyOtherAccessWithSeveralLatchedIsStillABusConflict() {
        try (MotherboardBus bus = twoCards()) {
            bus.read(0xC200);
            bus.read(0xC300);
            assertThrows(IllegalStateException.class, () -> bus.read(0xC800));
        }
    }

    @Test
    void cfffWithOneCardLatchedStillReadsThatCard() {
        try (MotherboardBus bus = twoCards()) {
            bus.read(0xC200);
            assertEquals(0x22, bus.read(0xCFFF), "one card latched: the read is that card's byte");
            bus.read(0xC300); // releasing slot 2 means selecting slot 3 is no conflict
            assertEquals(0x33, bus.read(0xC800));
        }
    }
}
