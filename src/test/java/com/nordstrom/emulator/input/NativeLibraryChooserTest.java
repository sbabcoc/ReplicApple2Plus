package com.nordstrom.emulator.input;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every platform the jar covers, checked without owning any of the hardware. */
class NativeLibraryChooserTest {

    private static Optional<String> choose(String os, String arch) {
        return NativeLibraryChooser.choose(os, arch);
    }

    @Test
    void macOsPicksTheDylibForItsChip() {
        assertEquals(Optional.of("libjamepadarm64.dylib"), choose("Mac OS X", "aarch64"));
        assertEquals(Optional.of("libjamepad64.dylib"), choose("Mac OS X", "x86_64"));
        assertEquals(Optional.of("libjamepad64.dylib"), choose("Mac OS X", "amd64"));
    }

    @Test
    void linuxPicksTheSharedObjectForItsCpu() {
        assertEquals(Optional.of("libjamepad64.so"), choose("Linux", "amd64"));
        assertEquals(Optional.of("libjamepad64.so"), choose("Linux", "x86_64"));
        assertEquals(Optional.of("libjamepadarm64.so"), choose("Linux", "aarch64"));
        assertEquals(Optional.of("libjamepadarm.so"), choose("Linux", "arm"));
        assertEquals(Optional.of("libjamepadarm.so"), choose("Linux", "armv7l"));
        assertEquals(Optional.of("libjamepad.so"), choose("Linux", "x86"));
        assertEquals(Optional.of("libjamepad.so"), choose("Linux", "i386"));
    }

    @Test
    void windowsPicksTheDllForItsWidth() {
        assertEquals(Optional.of("jamepad64.dll"), choose("Windows 11", "amd64"));
        assertEquals(Optional.of("jamepad64.dll"), choose("Windows 10", "x86_64"));
        assertEquals(Optional.of("jamepad.dll"), choose("Windows 10", "x86"));
    }

    @Test
    void combinationsTheJarHasNoLibraryForYieldNothingRatherThanAGuess() {
        assertEquals(Optional.empty(), choose("Windows 11", "aarch64"), "the jar has no Windows-on-ARM library");
        assertEquals(Optional.empty(), choose("Mac OS X", "ppc"));
        assertEquals(Optional.empty(), choose("Mac OS X", "arm"));
        assertEquals(Optional.empty(), choose("FreeBSD", "amd64"));
        assertEquals(Optional.empty(), choose("SunOS", "sparcv9"));
        assertEquals(Optional.empty(), choose("", ""));
    }

    @Test
    void matchingIgnoresCase() {
        assertEquals(Optional.of("libjamepad64.so"), choose("LINUX", "AMD64"));
    }
}
