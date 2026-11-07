package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotherboardBusReleaseTest {

    /** Counts its releases. */
    static final class Releasing extends TestCards.Base {
        int releases;

        @Override
        public String getShortName() {
            return "releasing";
        }

        @Override
        public String getDisplayName() {
            return "Releasing";
        }

        @Override
        public void release() {
            releases++;
        }
    }

    @Test
    void releasesEveryConfiguredCardOnce() {
        SlotCard[] slots = new SlotCard[8];
        Releasing slot0 = new Releasing();
        Releasing slot3 = new Releasing();
        slots[0] = slot0;
        slots[3] = slot3;
        try (MotherboardBus bus = MotherboardBus.withoutAudio(slots)) {
            bus.releaseCards(); // empty slots are skipped
        }
        assertEquals(1, slot0.releases);
        assertEquals(1, slot3.releases);
    }
}
