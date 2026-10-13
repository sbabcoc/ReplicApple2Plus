package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The card's 6850 serial interface, as CP/M's 6502 server drives it
 * (TRANSFER-CARD.md 8.2): master reset at startup, then status polls and
 * reads through the data register. The reader delivers the CP/M adapter's
 * Intel HEX followed by ^Z.
 */
class HostTransferCardSerialTest {

    private static final int CPM_END_OF_FILE = 0x1A;

    private final HostTransferCard card = new HostTransferCard();

    @Test
    void statusAlwaysOffersAByteAndRoomToTransmit() {
        int status = card.readIoSwitch(Protocol.REG_SERIAL_STATUS);
        assertEquals(Protocol.SERIAL_RECEIVE_FULL, status & Protocol.SERIAL_RECEIVE_FULL);
        assertEquals(Protocol.SERIAL_TRANSMIT_EMPTY, status & Protocol.SERIAL_TRANSMIT_EMPTY);
    }

    @Test
    void theReaderDeliversTheAdapterThenEndOfFile() throws IOException {
        initializeAsCpmDoes();
        assertArrayEquals(adapterThenEndOfFile(), readUntilEndOfFile());
    }

    @Test
    void theReaderStartsOverAfterEachEndOfFile() throws IOException {
        initializeAsCpmDoes();
        readUntilEndOfFile();
        assertArrayEquals(adapterThenEndOfFile(), readUntilEndOfFile(), "a second PIP gets a whole copy");
    }

    @Test
    void aMasterResetRewindsAnInterruptedDelivery() throws IOException {
        initializeAsCpmDoes();
        for (int i = 0; i < 10; i++) {
            card.readIoSwitch(Protocol.REG_SERIAL_DATA); // a PIP interrupted part way
        }
        initializeAsCpmDoes(); // the next cold boot
        assertArrayEquals(adapterThenEndOfFile(), readUntilEndOfFile());
    }

    @Test
    void transmittedBytesAndOtherControlValuesChangeNothing() throws IOException {
        initializeAsCpmDoes();
        int first = card.readIoSwitch(Protocol.REG_SERIAL_DATA);
        card.writeIoSwitch(Protocol.REG_SERIAL_DATA, 'X');
        card.writeIoSwitch(Protocol.REG_SERIAL_STATUS, 0x15);
        byte[] rest = readUntilEndOfFile();
        byte[] expected = adapterThenEndOfFile();
        assertEquals(expected[0] & 0xFF, first);
        assertEquals(expected.length - 1, rest.length, "neither the punch byte nor $15 disturbed the reader");
    }

    /** CP/M's server initializes a 6850 card by writing $03 (master reset), then $15. */
    private void initializeAsCpmDoes() {
        card.writeIoSwitch(Protocol.REG_SERIAL_STATUS, Protocol.SERIAL_MASTER_RESET);
        card.writeIoSwitch(Protocol.REG_SERIAL_STATUS, 0x15);
    }

    private byte[] readUntilEndOfFile() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 100_000; i++) {
            int value = card.readIoSwitch(Protocol.REG_SERIAL_DATA);
            out.write(value);
            if (value == CPM_END_OF_FILE) {
                return out.toByteArray();
            }
        }
        throw new AssertionError("the reader never sent ^Z");
    }

    private static byte[] adapterThenEndOfFile() throws IOException {
        try (InputStream in = HostTransferCard.class.getResourceAsStream("xfer.hex")) {
            assertNotNull(in, "xfer.hex resource");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(in.readAllBytes());
            out.write(CPM_END_OF_FILE);
            return out.toByteArray();
        }
    }
}
