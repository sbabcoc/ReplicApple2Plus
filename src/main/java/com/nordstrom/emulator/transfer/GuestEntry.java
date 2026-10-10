package com.nordstrom.emulator.transfer;

/**
 * One entry of a guest directory listing, as the adapter reports it. An
 * adapter reads only what a listing needs (TRANSFER-CARD.md 5.2): when that
 * leaves the aux value or the exact size unknown, it says so, and a later
 * READ supplies the type.
 *
 * @param name       the guest's name for it
 * @param directory  true for a directory
 * @param type       its type (tag and aux value), opaque to the emulator
 * @param attributes its attribute byte, opaque to the emulator
 * @param size       its size in bytes, as the guest reports it
 * @param auxKnown   false if the aux value isn't known until the file is read
 * @param sizeExact  false if the size is approximate (e.g. sectors x 256)
 */
public record GuestEntry(String name, boolean directory, FileType type, int attributes, long size,
                         boolean auxKnown, boolean sizeExact) {

    /**
     * An entry whose aux value and size are both exact.
     *
     * @param name       the guest's name for it
     * @param directory  true for a directory
     * @param type       its type
     * @param attributes its attribute byte
     * @param size       its size in bytes
     */
    public GuestEntry(String name, boolean directory, FileType type, int attributes, long size) {
        this(name, directory, type, attributes, size, true, true);
    }
}
