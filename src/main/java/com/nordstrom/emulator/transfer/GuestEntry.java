package com.nordstrom.emulator.transfer;

/**
 * One entry of a guest directory listing, as the adapter reports it.
 *
 * @param name       the guest's name for it
 * @param directory  true for a directory
 * @param type       its type (tag and aux value), opaque to the emulator
 * @param attributes its attribute byte, opaque to the emulator
 * @param size       its size in bytes, as the guest reports it (may be approximate, e.g. sectors x 256)
 */
public record GuestEntry(String name, boolean directory, FileType type, int attributes, long size) {
}
