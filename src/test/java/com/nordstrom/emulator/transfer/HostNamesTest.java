package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostNamesTest {

    private static final Capabilities PRODOS = Capabilities.parse(FakeAdapter.proDosLike());
    private static final Capabilities DOS = Capabilities.parse(FakeAdapter.dosLike());

    private static void roundTrips(String guestName, FileType type, int attributes, Capabilities caps) {
        String host = HostNames.encode(guestName, type, attributes, caps);
        HostNames.Decoded back = HostNames.decode(host);
        assertEquals(guestName, back.guestName(), host);
        assertEquals(type, back.typeOr(caps, false), host);
        assertEquals(attributes, back.attributesOr(caps), host);
        assertEquals(caps.isConvertibleText(type), back.text(), host);
        assertTrue(host.chars().noneMatch(c -> "/\\:*?\"<>|".indexOf(c) >= 0 || c < 0x20 || c >= 0x7F), host);
    }

    @Test
    void plainTextWithDefaultsIsJustDotTxt() {
        assertEquals("README.TXT", HostNames.encode("README", new FileType("TXT", 0), 0xE3, PRODOS));
        roundTrips("README", new FileType("TXT", 0), 0xE3, PRODOS);
    }

    @Test
    void binariesCarryTheirTypeAndAuxValue() {
        assertEquals("GAME#BIN,2000", HostNames.encode("GAME", new FileType("BIN", 0x2000), 0xE3, PRODOS));
        roundTrips("GAME", new FileType("BIN", 0x2000), 0xE3, PRODOS);
        roundTrips("SYSFILE", new FileType("SYS", 0x2000), 0xE3, PRODOS);
    }

    @Test
    void nonDefaultAttributesAreKept() {
        assertEquals("GAME#BIN,2000,21", HostNames.encode("GAME", new FileType("BIN", 0x2000), 0x21, PRODOS));
        roundTrips("GAME", new FileType("BIN", 0x2000), 0x21, PRODOS);
        assertEquals("NOTES#TXT,0000,21.TXT", HostNames.encode("NOTES", new FileType("TXT", 0), 0x21, PRODOS));
        roundTrips("NOTES", new FileType("TXT", 0), 0x21, PRODOS);
    }

    @Test
    void randomAccessTextKeepsItsRecordLengthAndIsNotMarkedAsConverted() {
        String host = HostNames.encode("DATA", new FileType("TXT", 128), 0xE3, PRODOS);
        assertEquals("DATA#TXT,0080", host);
        roundTrips("DATA", new FileType("TXT", 128), 0xE3, PRODOS);
    }

    @Test
    void charactersUnsafeOnAnyHostAreEscapedAndRestored() {
        // DOS 3.3 allows nearly anything in a name, including these
        String nasty = "A/B\\C:D*E?F\"G<H>I|J#K%L,M.";
        roundTrips(nasty, new FileType("B", 0x0800), 0, DOS);
        roundTrips("TRAILING SPACE ", new FileType("T", 0), 0, DOS);
        roundTrips("HIGH\u00C1", new FileType("B", 0), 0, DOS);
        roundTrips("CON", new FileType("T", 0), 0, DOS);
    }

    @Test
    void aCommaInATagIsEscapedSoTheFieldsStaySeparate() {
        roundTrips("X", new FileType("A,B", 1), 0, DOS);
    }

    @Test
    void hostNamesOutsideTheConventionAreAllName() {
        HostNames.Decoded d = HostNames.decode("photo.png");
        assertEquals("photo.png", d.guestName());
        assertNull(d.type());
        assertFalse(d.text());
        assertEquals(PRODOS.defaultBinaryType(), d.typeOr(PRODOS, false));
        assertEquals(PRODOS.defaultTextType(), d.typeOr(PRODOS, true), "content says text");

        HostNames.Decoded notes = HostNames.decode("notes.txt");
        assertEquals("notes", notes.guestName());
        assertTrue(notes.text(), "the .TXT suffix is case-insensitive");
        assertEquals(PRODOS.defaultTextType(), notes.typeOr(PRODOS, false));

        assertEquals("ODD#X", HostNames.decode("ODD#X").guestName(), "not the convention: all name");
        assertEquals("ODD#BIN,ZZ", HostNames.decode("ODD#BIN,ZZ").guestName());
        assertEquals(".TXT", HostNames.decode(".TXT").guestName(), "a bare suffix is a name");
    }
}
