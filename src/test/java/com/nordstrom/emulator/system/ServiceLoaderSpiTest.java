package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms, empirically and with NO involvement from {@link CardTypes}'
 * own reader, that {@code META-INF/services/com.nordstrom.emulator.system.SlotCard}
 * is a genuine, valid Java SPI declaration file: real
 * {@link ServiceLoader#load(Class)} can enumerate and construct every
 * provider it lists without throwing {@code ServiceConfigurationError}.
 * <p>
 * This is the actual proof behind the claim in {@link CardTypes}' and
 * {@link SlotCard}'s Javadoc -- not merely an assertion that the
 * no-arg-constructor-plus-{@code configure()} contract "should" work
 * with {@code ServiceLoader}, but a test that it demonstrably does.
 */
class ServiceLoaderSpiTest {

    @Test
    void realServiceLoaderCanEnumerateEveryRegisteredSlotCard() {
        assertDoesNotThrow(() -> {
            for (SlotCard card : ServiceLoader.load(SlotCard.class)) {
                // Merely iterating is the test: ServiceLoader constructs
                // each provider as it's reached, so a ServiceConfigurationError
                // here would mean some registered card no longer has a
                // genuine no-arg constructor.
            }
        });
    }

    @Test
    void everyRegisteredCardDeclaresItsNamesAndSettings() {
        CardCatalog.Catalog catalog = CardCatalog.read(ServiceLoaderSpiTest.class.getClassLoader());
        assertTrue(catalog.problems().isEmpty(), "every registered card describes itself: " + catalog.problems());
        assertTrue(catalog.cards().size() >= 5, "the project's own five cards at least: " + catalog.cards());
    }
}
