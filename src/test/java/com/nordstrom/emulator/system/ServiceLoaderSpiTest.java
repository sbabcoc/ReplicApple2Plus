package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
 * <p>
 * NOTE: written and believed correct against the JUnit 5 Jupiter API,
 * consistent with this project's other tests, but this specific test
 * also depends on there being at least one real, compiled {@code
 * SlotCard} provider registered by the time it runs -- currently there
 * are none (see the service file's own placeholder example), so this
 * test will need a genuine provider added before it can meaningfully
 * pass rather than trivially enumerate zero providers.
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
}
