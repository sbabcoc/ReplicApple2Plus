package com.nordstrom.emulator.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exhaustively checks Arithmetic6502.arr() in decimal mode against a
 * SEPARATE, independently-transcribed copy of the reference pseudocode
 * (West &amp; M&auml;kel&auml;'s 64doc; see nesdev.org/6502_cpu.txt), for
 * every one of the 131,072 (A, operand, carry-in) combinations.
 * <p>
 * NOTE: written and believed correct against the JUnit 5 Jupiter API, but
 * NOT compiled or executed against a real JUnit runtime -- see
 * {@link FunctionalTestHarnessTest} for why. Please run `./gradlew test`
 * to confirm before relying on this.
 */
class ArrDecimalCrossCheckTest {

    @Test
    void arrDecimalMatchesReferenceForAllCombinations() {
        StringBuilder failures = new StringBuilder();
        int mismatches = 0;

        for (int a = 0; a < 256; a++) {
            for (int operand = 0; operand < 256; operand++) {
                for (int c = 0; c <= 1; c++) {
                    Status6502 status = new Status6502();
                    if (c == 1) status.setFlag(Status6502.CARRY); else status.clearFlag(Status6502.CARRY);
                    status.setFlag(Status6502.DECIMAL);

                    int actualA = Arithmetic6502.arr(a, operand, status);
                    int actualFlags = statusToNVZC(status);

                    int[] ref = referenceArrDecimal(a, operand, c);
                    int refA = ref[0];
                    int refFlags = ref[1];

                    if (actualA != refA || actualFlags != refFlags) {
                        mismatches++;
                        if (mismatches <= 20) {
                            failures.append(String.format(
                                "a=$%02X op=$%02X c=%d: impl A=$%02X flags=%s | ref A=$%02X flags=%s%n",
                                a, operand, c, actualA, flagString(actualFlags), refA, flagString(refFlags)));
                        }
                    }
                }
            }
        }

        assertEquals(0, mismatches, mismatches + " mismatch(es) out of 131072 combinations:\n" + failures);
    }

    /** Independently transcribed, fresh, directly from the pseudocode -- not derived from or copied out of Arithmetic6502.java. */
    private static int[] referenceArrDecimal(int a, int operand, int oldC) {
        int t = a & operand;
        int ah = t >> 4;
        int al = t & 0x0F;

        int n = oldC;
        int result = (t >> 1) | (oldC << 7);
        int z = (result == 0) ? 1 : 0;
        int v = (((t ^ result) & 0x40) != 0) ? 1 : 0;

        if (al + (al & 1) > 5) {
            result = (result & 0xF0) | ((result + 6) & 0x0F);
        }

        int c;
        if (ah + (ah & 1) > 5) {
            c = 1;
            result = (result + 0x60) & 0xFF;
        } else {
            c = 0;
        }

        int flags = (n != 0 ? Status6502.NEGATIVE : 0)
                  | (v != 0 ? Status6502.OVERFLOW : 0)
                  | (z != 0 ? Status6502.ZERO : 0)
                  | (c != 0 ? Status6502.CARRY : 0);
        return new int[] { result, flags };
    }

    private static int statusToNVZC(Status6502 status) {
        return status.toPushedByteSoftware() & (Status6502.NEGATIVE | Status6502.OVERFLOW | Status6502.ZERO | Status6502.CARRY);
    }

    private static String flagString(int flags) {
        return "" + ((flags & Status6502.NEGATIVE) != 0 ? 'N' : '-')
                  + ((flags & Status6502.OVERFLOW) != 0 ? 'V' : '-')
                  + ((flags & Status6502.ZERO) != 0 ? 'Z' : '-')
                  + ((flags & Status6502.CARRY) != 0 ? 'C' : '-');
    }
}
