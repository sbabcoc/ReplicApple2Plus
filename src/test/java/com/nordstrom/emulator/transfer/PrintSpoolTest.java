package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintSpoolTest {

    @Test
    void aJobIsTakenOnlyOnceIdleAndOnlyOnce() throws InterruptedException {
        PrintSpool spool = new PrintSpool();
        assertNull(spool.takeIfIdle(0), "nothing printed: no job");
        spool.append(0xC1);
        spool.append(0x8D);
        assertTrue(spool.hasJob());
        assertNull(spool.takeIfIdle(60_000_000_000L), "still printing, as far as a minute's idle goes");
        Thread.sleep(20);
        assertArrayEquals(new byte[] {(byte) 0xC1, (byte) 0x8D}, spool.takeIfIdle(10_000_000L));
        assertFalse(spool.hasJob());
        assertNull(spool.takeIfIdle(0), "taken: the next job starts empty");
    }

    @Test
    void printedTextBecomesHostText() {
        byte[] printed = {(byte) 0xB1, (byte) 0xB0, (byte) 0xA0, (byte) 0xC5, (byte) 0xCE, (byte) 0xC4, (byte) 0x8D};
        assertArrayEquals("10 END\n".getBytes(StandardCharsets.US_ASCII), PrintText.toHost(printed));
    }

    @Test
    void theCardsPrintRegisterFeedsTheSpoolWhileCapturing() {
        HostTransferCard card = new HostTransferCard();
        card.writeIoSwitch(Protocol.REG_PHASE, 2); // capturing, as the firmware sets it
        card.writeIoSwitch(Protocol.REG_PRINT, 0xC1);
        assertArrayEquals(new byte[] {(byte) 0xC1}, card.printSpool().takeIfIdle(0));
    }

    /**
     * A program probing the slots for an Uthernet II wrote $80 then $03 to
     * the card's $C0n4 -- the print register -- with no listing under way,
     * and the two bytes were offered as a program listing to save.
     */
    @Test
    void printingOutsideACaptureIsIgnored() {
        HostTransferCard card = new HostTransferCard();
        card.writeIoSwitch(Protocol.REG_PRINT, 0x80);
        card.writeIoSwitch(Protocol.REG_PRINT, 0x03);
        assertFalse(card.printSpool().hasJob(), "phase 0: nothing to save");
        card.writeIoSwitch(Protocol.REG_PHASE, 1); // typing LIST: its echo goes to the screen, not here
        card.writeIoSwitch(Protocol.REG_PRINT, 0xCC);
        assertFalse(card.printSpool().hasJob(), "phase 1: nothing to save");
    }

    @Test
    void resetDuringACaptureAbandonsIt() {
        HostTransferCard card = new HostTransferCard();
        card.writeIoSwitch(Protocol.REG_PHASE, 2); // capturing
        card.writeIoSwitch(Protocol.REG_PRINT, 0xB1);
        card.writeIoSwitch(Protocol.REG_PRINT, 0xB0);
        card.onReset();
        assertFalse(card.printSpool().hasJob(), "a partial listing isn't offered for saving");
    }

    @Test
    void resetAfterACaptureKeepsTheListing() {
        HostTransferCard card = new HostTransferCard();
        card.writeIoSwitch(Protocol.REG_PHASE, 2);
        card.writeIoSwitch(Protocol.REG_PRINT, 0xB1);
        card.writeIoSwitch(Protocol.REG_PRINT, 0x8D);
        card.writeIoSwitch(Protocol.REG_PHASE, 0); // the capture is over, as the firmware ends it
        card.writeIoSwitch(Protocol.REG_TRIM, 0);
        card.onReset(); // within the idle wait, before the job is taken
        assertArrayEquals(new byte[] {(byte) 0xB1, (byte) 0x8D}, card.printSpool().takeIfIdle(0),
            "a finished listing is still saved");
    }

    @Test
    void aListingIsTrimmedToItsNonBlankLines() {
        PrintSpool spool = new PrintSpool();
        // Applesoft's leading Return, an Integer-style line, BASIC's fresh line and prompt
        for (int b : new int[] {0x8D, 0xA0, 0xB1, 0xB0, 0x8D, 0x8D, 0xDD}) {
            spool.append(b);
        }
        spool.trimListing();
        assertArrayEquals(new byte[] {(byte) 0xA0, (byte) 0xB1, (byte) 0xB0, (byte) 0x8D}, spool.takeIfIdle(0),
            "blank lines at both ends and the prompt go; spaces within the line stay");
    }
}
