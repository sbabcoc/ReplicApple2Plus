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
    void theCardsPrintRegisterFeedsTheSpool() {
        HostTransferCard card = new HostTransferCard();
        card.writeIoSwitch(Protocol.REG_PRINT, 0xC1);
        assertArrayEquals(new byte[] {(byte) 0xC1}, card.printSpool().takeIfIdle(0));
    }
}
