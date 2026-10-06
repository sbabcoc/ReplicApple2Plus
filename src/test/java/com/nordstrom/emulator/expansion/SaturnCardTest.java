package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaturnCardTest {

    private static SaturnCard card() {
        SaturnCard card = new SaturnCard();
        card.configure(new Properties());
        return card;
    }

    /** Selects bank 1-8 by touching its bank-select address ($C0N4-$C0N7, $C0NC-$C0NF). */
    private static void selectBank(SaturnCard card, int bank) {
        int index = bank - 1;
        card.readIoSwitch(0x04 | (index & 3) | (index >= 4 ? 0x08 : 0));
    }

    /** RAM read, write enabled, sub-bank A ($C0N3 twice) or B ($C0NB twice). */
    private static void readWriteRam(SaturnCard card, boolean subBankB) {
        int offset = subBankB ? 0x0B : 0x03;
        card.readIoSwitch(offset);
        card.readIoSwitch(offset);
    }

    @Test
    void powersUpReadingRomWriteProtectedSubBankAOfBank1() {
        SaturnCard card = card();
        assertFalse(card.isReadingRam());
        assertFalse(card.isWriteEnabled());
        assertFalse(card.isSubBankB());
        assertEquals(1, card.selectedBank());
    }

    @Test
    void stateSelectFollowsTheLanguageCardTruthTable() {
        // {offset, RAM read, write-enabled after a double access, sub-bank B}
        int[][] table = {
            {0x0, 1, 0, 0}, {0x1, 0, 1, 0}, {0x2, 0, 0, 0}, {0x3, 1, 1, 0},
            {0x8, 1, 0, 1}, {0x9, 0, 1, 1}, {0xA, 0, 0, 1}, {0xB, 1, 1, 1},
        };
        for (int[] row : table) {
            SaturnCard card = card();
            card.readIoSwitch(row[0]);
            card.readIoSwitch(row[0]);
            assertEquals(row[1] == 1, card.isReadingRam(), "RAM read, $C0N" + Integer.toHexString(row[0]));
            assertEquals(row[2] == 1, card.isWriteEnabled(), "write enable, $C0N" + Integer.toHexString(row[0]));
            assertEquals(row[3] == 1, card.isSubBankB(), "sub-bank, $C0N" + Integer.toHexString(row[0]));
        }
    }

    @Test
    void bankSelectAddressesPickBanksOneThroughEightAndChangeNothingElse() {
        SaturnCard card = card();
        readWriteRam(card, true);
        int[] offsets = {0x4, 0x5, 0x6, 0x7, 0xC, 0xD, 0xE, 0xF};
        for (int i = 0; i < offsets.length; i++) {
            card.readIoSwitch(offsets[i]);
            assertEquals(i + 1, card.selectedBank(), "$C0N" + Integer.toHexString(offsets[i]));
            assertTrue(card.isReadingRam() && card.isWriteEnabled() && card.isSubBankB(),
                "bank select must leave the read/write/sub-bank state alone");
        }
    }

    @Test
    void writesCountTowardWriteEnableUnlikeTheLanguageCard() {
        SaturnCard card = card();
        card.writeIoSwitch(0x1, 0);
        card.writeIoSwitch(0x1, 0);
        assertTrue(card.isWriteEnabled(), "two writes to $C0N1 write-enable the Saturn (\"reading or writing\")");

        LanguageCard languageCard = new LanguageCard();
        languageCard.writeIoSwitch(0x1, 0);
        languageCard.writeIoSwitch(0x1, 0);
        assertFalse(languageCard.isWriteEnabled(), "the Language Card counts reads only");
    }

    @Test
    void eachBankAndSubBankIsIndependentMemory() {
        SaturnCard card = card();
        for (int bank = 1; bank <= 8; bank++) {
            selectBank(card, bank);
            readWriteRam(card, false);
            card.writeUpperMemory(0x0000, 0x10 + bank);  // $D000, sub-bank A
            card.writeUpperMemory(0x1000, 0x30 + bank);  // $E000, this bank's 8K
            readWriteRam(card, true);
            card.writeUpperMemory(0x0000, 0x20 + bank);  // $D000, sub-bank B
        }
        for (int bank = 1; bank <= 8; bank++) {
            selectBank(card, bank);
            readWriteRam(card, false);
            assertEquals(0x10 + bank, card.readUpperMemory(0x0000).getAsInt(), "bank " + bank + " sub-bank A");
            assertEquals(0x30 + bank, card.readUpperMemory(0x1000).getAsInt(), "bank " + bank + " $E000");
            readWriteRam(card, true);
            assertEquals(0x20 + bank, card.readUpperMemory(0x0000).getAsInt(), "bank " + bank + " sub-bank B");
            assertEquals(0x30 + bank, card.readUpperMemory(0x1000).getAsInt(),
                "$E000-$FFFF is shared by both sub-banks of a bank");
        }
    }

    @Test
    void writesAreIgnoredWhileWriteProtectedAndReadsFallThroughToRomWhenSelected() {
        SaturnCard card = card();
        readWriteRam(card, false);
        card.writeUpperMemory(0x0100, 0x5A);
        card.readIoSwitch(0x0); // RAM read, write protected
        card.writeUpperMemory(0x0100, 0xA5);
        assertEquals(0x5A, card.readUpperMemory(0x0100).getAsInt());
        card.readIoSwitch(0x2); // ROM read
        assertTrue(card.readUpperMemory(0x0100).isEmpty(), "ROM read: the card stops driving the bus");
    }

    @Test
    void worksFromASlotOtherThanZero() {
        SaturnCard saturn = card();
        SlotCard[] slots = new SlotCard[8];
        slots[4] = saturn;
        try (MotherboardBus bus = MotherboardBus.withoutAudio(slots)) {
            bus.read(0xC0C3); // slot 4's $C0N3, twice: RAM read, write enabled
            bus.read(0xC0C3);
            bus.write(0xD123, 0x77);
            assertEquals(0x77, bus.read(0xD123), "the card took over $D000-$FFFF from slot 4");
            assertFalse(saturn.hasRom());
            bus.read(0xC400); // no ROM: the floating bus, never readRom
        }
    }

    @Test
    void onlyOneCardMayTakeOverUpperMemory() {
        SlotCard[] slots = new SlotCard[8];
        slots[0] = new LanguageCard();
        slots[4] = card();
        assertThrows(IllegalStateException.class, () -> MotherboardBus.withoutAudio(slots));
    }
}
