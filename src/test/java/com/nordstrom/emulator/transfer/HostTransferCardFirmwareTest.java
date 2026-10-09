package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The committed firmware image, as the card serves it. */
class HostTransferCardFirmwareTest {

    private final HostTransferCard card = new HostTransferCard();

    @Test
    void theCardHasItsOwnRomAndClaimsTheExpansionWindow() {
        assertTrue(card.hasRom());
        assertTrue(card.wantsExpansionRom());
    }

    @Test
    void theSlotPageCarriesNoSignatureThatOtherSoftwareLooksFor() {
        // The Autostart ROM boots from a card whose $Cn01/$Cn03/$Cn05 read $20/$00/$03,
        // and Pascal 1.1 firmware is recognized by $Cn05/$Cn07 = $38/$18.
        boolean diskBoot = card.readRom(1) == 0x20 && card.readRom(3) == 0x00 && card.readRom(5) == 0x03;
        boolean pascal = card.readRom(5) == 0x38 && card.readRom(7) == 0x18;
        assertFalse(diskBoot, "would be mistaken for a disk controller");
        assertFalse(pascal, "would be mistaken for a Pascal 1.1 device");
        assertEquals(0x48, card.readRom(0), "entry begins PHA: saving the character to print");
    }

    @Test
    void theRomBankRegisterSelectsWhatTheExpansionWindowShows() {
        card.writeIoSwitch(Protocol.REG_STATUS, 0);
        int[] bank0 = {card.readExpansionRom(0), card.readExpansionRom(1), card.readExpansionRom(2)};
        card.writeIoSwitch(Protocol.REG_STATUS, 1);
        int[] bank1 = {card.readExpansionRom(0), card.readExpansionRom(1), card.readExpansionRom(2)};
        assertFalse(java.util.Arrays.equals(bank0, bank1), "banks 0 and 1 hold different code");
        assertEquals(0x00, bank1[0], "bank 1 starts with the agent's finish pointer, zeroed until set");
        card.writeIoSwitch(Protocol.REG_STATUS, 2);
        assertEquals(0x00, card.readExpansionRom(0), "bank 2 starts the DOS 3.3 agent: its finish pointer too");
        card.writeIoSwitch(Protocol.REG_STATUS, 3);
        int bank3 = card.readExpansionRom(0);
        assertTrue(bank3 != 0xFF, "the DOS agent's image runs on into bank 3");
        card.writeIoSwitch(Protocol.REG_STATUS, 9);
        assertEquals(0xFF, card.readExpansionRom(0), "a bank past the end of the image reads $FF");
    }
}
