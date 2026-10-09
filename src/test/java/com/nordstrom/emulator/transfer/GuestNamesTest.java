package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuestNamesTest {

    private static final Capabilities PRODOS = Capabilities.parse(FakeAdapter.proDosLike());
    private static final Capabilities DOS = Capabilities.parse(FakeAdapter.dosLike());

    @Test
    void proDosNamesAreUpperCaseLettersDigitsAndPeriods() {
        assertEquals("MY.NOTES", GuestNames.suggest("my notes", PRODOS));
        assertEquals("README", GuestNames.suggest("readme", PRODOS));
        assertEquals("CAF.", GuestNames.suggest("caf\u00e9", PRODOS));
    }

    @Test
    void aProDosNameStartsWithALetter() {
        assertEquals("A2024.LOG", GuestNames.suggest("2024.log", PRODOS));
        assertEquals("A.PROFILE", GuestNames.suggest(".profile", PRODOS));
    }

    @Test
    void longNamesAreCutToTheMaximum() {
        assertEquals("ABCDEFGHIJKLMNO", GuestNames.suggest("abcdefghijklmnopqrstuvwxyz", PRODOS));
        assertEquals(15, GuestNames.suggest("a very long file name indeed", PRODOS).length());
    }

    @Test
    void anEmptyNameGetsAPlaceholder() {
        assertEquals("UNTITLED", GuestNames.suggest("", PRODOS));
    }

    @Test
    void rulesComeOnlyFromTheCapabilityRecord() {
        // the DOS-like record declares no extra characters and no case rule:
        // other characters are dropped, case is kept, and up to 30 characters fit
        assertEquals("MixedCaseName", GuestNames.suggest("Mixed Case Name", DOS));
        assertEquals(30, GuestNames.suggest("x".repeat(40), DOS).length());
    }
}
