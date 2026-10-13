package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final Capabilities CPM = Capabilities.parse(FakeAdapter.cpmLike());

    @Test
    void cpmNamesAreEightPlusThree() {
        assertTrue(GuestNames.isValid("PIP.COM", CPM));
        assertTrue(GuestNames.isValid("README", CPM), "the extension is optional");
        assertTrue(GuestNames.isValid("LONGNAME.TXT", CPM));
        assertFalse(GuestNames.isValid("TOOLONGNAME.TXT", CPM), "name part longer than 8");
        assertFalse(GuestNames.isValid("NAME.TEXT", CPM), "extension longer than 3");
        assertFalse(GuestNames.isValid("A.B.C", CPM), "one dot only");
        assertFalse(GuestNames.isValid(".COM", CPM), "a name part is required");
        assertFalse(GuestNames.isValid("NAME.", CPM), "a dot needs an extension after it");
    }

    @Test
    void cpmForbidsItsSeparatorsAndWildcards() {
        for (String bad : new String[] {"A<B", "A>B", "A,B", "A;B", "A:B", "A=B", "A?B", "A*B", "A[B", "A]B", "A B"}) {
            assertFalse(GuestNames.isValid(bad, CPM), bad);
        }
        assertTrue(GuestNames.isValid("A-B_C!", CPM));
    }

    @Test
    void acceptableOnlyChangesCaseAndOtherwiseLeavesTheChoiceToTheUser() {
        assertEquals("PROG.ASM", GuestNames.acceptable("prog.asm", CPM));
        assertNull(GuestNames.acceptable("my program.asm", CPM), "a space: the user must name it");
        assertNull(GuestNames.acceptable("averylongname.asm", CPM), "too long: never shortened silently");
    }

    @Test
    void theRulesCanBeExplainedInWords() {
        assertEquals("1-8 characters, optionally a dot and 1-3 more; letters, digits and "
            + CPM.nameExtraChars(), GuestNames.describeRules(CPM));
        assertEquals("1-15 characters, starting with a letter; letters, digits and .", GuestNames.describeRules(PRODOS));
    }
}
