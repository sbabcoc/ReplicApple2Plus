package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MotherboardBusAudioTest {

    @Test
    void aBusBuiltForTestsNeverOpensTheHostAudioDevice() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            assertFalse(bus.speakerOutput().hasDevice(),
                "withoutAudio() must take the speaker's no-device path, not open a real line");
        }
    }
}
