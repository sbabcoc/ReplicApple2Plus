package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilitiesTest {

    @Test
    void readsEveryFieldOfTheRecord() {
        Capabilities caps = Capabilities.parse(FakeAdapter.proDosLike());
        assertEquals("PRODOS", caps.osName());
        assertEquals("2.4", caps.osVersion());
        assertTrue(caps.hierarchical());
        assertEquals(15, caps.maxNameLength());
        assertTrue(caps.upperCaseOnly());
        assertTrue(caps.startsWithLetter());
        assertEquals(".", caps.nameExtraChars());
        assertArrayEquals(new byte[] {0x0D}, caps.textLineEnd());
        assertFalse(caps.textHighBit());
        assertEquals(List.of("TXT"), caps.textTags());
        assertEquals(new FileType("TXT", 0), caps.defaultTextType());
        assertEquals(new FileType("BIN", 0), caps.defaultBinaryType());
        assertEquals(0xE3, caps.defaultAttributes());
    }

    @Test
    void unknownFieldsAreSkipped() {
        byte[] record = new Capabilities.Builder()
            .field(0x55, (byte) 1, (byte) 2, (byte) 3) // a field from some later protocol revision
            .field(Protocol.CAP_OS_NAME, "LATER")
            .field(0x7F) // empty, and unknown
            .build();
        assertEquals("LATER", Capabilities.parse(record).osName());
    }

    @Test
    void aFieldRunningPastTheEndIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> Capabilities.parse(new byte[] {Protocol.CAP_OS_NAME, 10, 'A'}));
    }

    @Test
    void randomAccessTextIsNotConvertible() {
        Capabilities caps = Capabilities.parse(FakeAdapter.proDosLike());
        assertTrue(caps.isConvertibleText(new FileType("TXT", 0)));
        assertFalse(caps.isConvertibleText(new FileType("TXT", 128)), "aux = record length: random access");
        assertFalse(caps.isConvertibleText(new FileType("BIN", 0)));
        Capabilities dos = Capabilities.parse(FakeAdapter.dosLike());
        assertTrue(dos.isConvertibleText(new FileType("T", 0)));
    }
}
