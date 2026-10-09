package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextConversionTest {

    private static final Capabilities PRODOS = Capabilities.parse(FakeAdapter.proDosLike());
    private static final Capabilities DOS = Capabilities.parse(FakeAdapter.dosLike());

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] highBit(String s) {
        byte[] b = ascii(s);
        for (int i = 0; i < b.length; i++) {
            b[i] |= (byte) 0x80;
        }
        return b;
    }

    @Test
    void proDosTextUsesCrWithTheHighBitClear() {
        assertArrayEquals(ascii("10 PRINT\n20 END\n"), TextConversion.toHost(ascii("10 PRINT\r20 END\r"), PRODOS));
        assertArrayEquals(ascii("10 PRINT\r20 END\r"), TextConversion.toGuest(ascii("10 PRINT\n20 END\n"), PRODOS));
    }

    @Test
    void dosTextSetsTheHighBit() {
        assertArrayEquals(ascii("HELLO\nWORLD\n"), TextConversion.toHost(highBit("HELLO\rWORLD\r"), DOS));
        assertArrayEquals(highBit("HELLO\rWORLD\r"), TextConversion.toGuest(ascii("HELLO\nWORLD\n"), DOS));
    }

    @Test
    void importAcceptsCrLfAndLoneCr() {
        byte[] expected = ascii("A\rB\rC\r");
        assertArrayEquals(expected, TextConversion.toGuest(ascii("A\r\nB\r\nC\r\n"), PRODOS));
        assertArrayEquals(expected, TextConversion.toGuest(ascii("A\rB\rC\r"), PRODOS));
        assertArrayEquals(expected, TextConversion.toGuest(ascii("A\nB\r\nC\r"), PRODOS));
    }

    @Test
    void aMultiByteLineEndingWorksBothWays() {
        Capabilities crlf = Capabilities.parse(new Capabilities.Builder()
            .field(Protocol.CAP_TEXT_LINE_END, (byte) 0x0D, (byte) 0x0A).build());
        assertArrayEquals(ascii("X\r\nY\r\n"), TextConversion.toGuest(ascii("X\nY\n"), crlf));
        assertArrayEquals(ascii("X\nY\n"), TextConversion.toHost(ascii("X\r\nY\r\n"), crlf));
    }

    @Test
    void plainTextDetection() {
        assertTrue(TextConversion.isPlainText(ascii("Hello,\tworld\r\n")));
        assertFalse(TextConversion.isPlainText(new byte[] {'A', 0, 'B'}));
        assertFalse(TextConversion.isPlainText("caf\u00e9".getBytes(StandardCharsets.UTF_8)));
    }
}
