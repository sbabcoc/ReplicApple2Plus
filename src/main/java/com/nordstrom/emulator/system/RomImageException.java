package com.nordstrom.emulator.system;

/**
 * A ROM image is missing, or isn't the image it should be. Distinct from
 * other startup failures because the user can fix it by choosing another
 * ROM folder ({@link RomFiles}), so the emulator offers that.
 */
public final class RomImageException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what's wrong, naming the image
     */
    public RomImageException(String message) {
        super(message);
    }
}
