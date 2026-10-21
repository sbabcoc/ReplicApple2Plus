package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardTypesTest {

    private static Properties props(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    @Test
    void aCardIsNamedByItsShortNameOnly() {
        ClassLoader loader = CardTypesTest.class.getClassLoader();
        assertTrue(CardTypes.create("languageCard", props(), loader) instanceof com.nordstrom.emulator.expansion.LanguageCard);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            CardTypes.create("com.nordstrom.emulator.expansion.LanguageCard", props(), loader));
        assertTrue(e.getMessage().startsWith("Unknown card type"), e.getMessage());
        assertTrue(e.getMessage().contains("languageCard"), "the message lists the known names: " + e.getMessage());
    }

    @Test
    void anUnknownTypeNamesTheProvidersThatCouldntBeLoaded(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir, TestCards.ThrowingCard.class.getName());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            CardTypes.create("testThrowing", props(), loader));
        assertTrue(e.getMessage().contains("left out:") && e.getMessage().contains("ThrowingCard"), e.getMessage());
    }

    @Test
    void aSettingTheCardDoesntDeclareIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            CardTypes.create("videoterm", props("display", "switched"), CardTypesTest.class.getClassLoader()));
        assertTrue(e.getMessage().contains("\"display\" is not a setting of Videx VideoTerm"), e.getMessage());
    }

    @Test
    void aValueOutsideItsSettingsRangeIsRefusedBeforeTheCardSeesIt(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir, TestCards.CountCard.class.getName());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            CardTypes.create("testCount", props("count", "11"), loader));
        assertTrue(e.getMessage().contains("from 1 to 10"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CardTypes.create("testCount", props("count", "many"), loader));
        TestCards.CountCard card = (TestCards.CountCard) CardTypes.create("testCount", props("count", "7"), loader);
        assertEquals(7, card.count);
    }

    @Test
    void aCardReadingASettingItDoesntDeclareIsCaught(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir, TestCards.SneakyCard.class.getName());
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            CardTypes.create("testSneaky", props(), loader));
        assertTrue(e.getMessage().contains("\"undeclared\""), e.getMessage());
    }

    @Test
    void aShortNameClaimedTwiceIsReportedAsAConflict(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir, TestCards.ClashA.class.getName(), TestCards.ClashB.class.getName());
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            CardTypes.create("testClash", props(), loader));
        assertTrue(e.getMessage().contains("claimed by more than one card"), e.getMessage());
    }
}
