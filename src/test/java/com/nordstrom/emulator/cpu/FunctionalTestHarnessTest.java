package com.nordstrom.emulator.cpu;

import com.nordstrom.emulator.MemoryBus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs Klaus2m5's 6502 functional-test ROM (see
 * github.com/Klaus2m5/6502_65C02_functional_tests) against the real
 * Cpu6502 and asserts it reaches the documented success trap at $3469.
 * <p>
 * NOTE: written and believed correct against the JUnit 5 Jupiter API, but
 * NOT compiled or executed against a real JUnit runtime -- this sandbox
 * has no route to Maven Central (repo1.maven.org returns 403, not on the
 * allowed-domains list) to fetch the junit-jupiter jars this needs.
 * Please run `./gradlew test` to confirm before relying on this.
 */
class FunctionalTestHarnessTest {

    private static final int START_ADDRESS = 0x0400;
    private static final int SUCCESS_TRAP_ADDRESS = 0x3469;
    private static final long MAX_STEPS = 200_000_000L;

    static final class FlatBus implements MemoryBus {
        final int[] mem = new int[0x10000];

        @Override public int read(int address) { return mem[address & 0xFFFF]; }
        @Override public void write(int address, int value) { mem[address & 0xFFFF] = value & 0xFF; }
    }

    @Test
    void functionalTestReachesSuccessTrap() throws IOException {
        FlatBus bus = new FlatBus();
        try (InputStream in = getClass().getResourceAsStream("/6502_functional_test.bin")) {
            if (in == null) {
                fail("Test resource 6502_functional_test.bin not found on the classpath -- "
                    + "expected at src/test/resources/6502_functional_test.bin");
                return;
            }
            byte[] image = in.readAllBytes();
            for (int i = 0; i < image.length; i++) {
                bus.mem[i] = image[i] & 0xFF;
            }
        }

        bus.mem[0xFFFC] = START_ADDRESS & 0xFF;
        bus.mem[0xFFFD] = (START_ADDRESS >> 8) & 0xFF;
        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC);

        for (long i = 0; i < MAX_STEPS; i++) {
            int beforePc = cpu.pc;
            cpu.step();
            if (cpu.pc == beforePc) {
                if (beforePc != SUCCESS_TRAP_ADDRESS) {
                    fail(String.format(
                        "Trapped at $%04X after %d steps (%d cycles) -- expected the success trap at $%04X. "
                        + "Look up $%04X in 6502_functional_test.lst to find which test failed.",
                        beforePc, i, cpu.cycleCount, SUCCESS_TRAP_ADDRESS, beforePc));
                }
                return;
            }
        }
        fail("No trap reached after " + MAX_STEPS + " steps -- PC=$" + Integer.toHexString(cpu.pc));
    }
}
