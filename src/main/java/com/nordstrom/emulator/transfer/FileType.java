package com.nordstrom.emulator.transfer;

/**
 * A guest file's type as its adapter reports it: an opaque tag (e.g. "BIN"
 * under ProDOS, "B" under DOS 3.3) and an auxiliary value (e.g. a load
 * address). The emulator never interprets either -- see TRANSFER-CARD.md 4.4.
 *
 * @param tag the type tag
 * @param aux the auxiliary value, 0-$FFFF
 */
public record FileType(String tag, int aux) {

    /**
     * Checks that the aux value fits in 16 bits.
     *
     * @param tag the type tag
     * @param aux the auxiliary value, 0-$FFFF
     */
    public FileType {
        if (aux < 0 || aux > 0xFFFF) {
            throw new IllegalArgumentException("aux value out of range: " + aux);
        }
    }
}
