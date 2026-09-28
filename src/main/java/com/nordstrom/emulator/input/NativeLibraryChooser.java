package com.nordstrom.emulator.input;

import java.util.Locale;
import java.util.Optional;

/**
 * Picks which of Jamepad's bundled native libraries fits this operating
 * system and CPU. A pure function of two strings -- the values of the
 * {@code os.name} and {@code os.arch} system properties -- so every
 * supported combination can be checked without owning the hardware.
 * <p>
 * The file names are those actually present in jamepad-2.30.0.0.jar,
 * whose architectures were confirmed against the binaries themselves. A
 * combination the jar has no library for yields empty, not a guess.
 */
final class NativeLibraryChooser {

    private enum Os { MAC, LINUX, WINDOWS }

    private enum Cpu { X64, X86, ARM64, ARM32 }

    /**
     * Chooses the native library for a platform.
     *
     * @param osName the {@code os.name} property, e.g. "Mac OS X"
     * @param osArch the {@code os.arch} property, e.g. "aarch64"
     * @return the library's file name within the jar, or empty if none fits
     */
    static Optional<String> choose(String osName, String osArch) {
        Os os = os(osName.toLowerCase(Locale.ROOT));
        Cpu cpu = cpu(osArch.toLowerCase(Locale.ROOT));
        if (os == null || cpu == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (os) {
            case MAC -> switch (cpu) {
                case ARM64 -> "libjamepadarm64.dylib";
                case X64 -> "libjamepad64.dylib";
                default -> null;
            };
            case LINUX -> switch (cpu) {
                case X64 -> "libjamepad64.so";
                case X86 -> "libjamepad.so";
                case ARM64 -> "libjamepadarm64.so";
                case ARM32 -> "libjamepadarm.so";
            };
            case WINDOWS -> switch (cpu) {
                case X64 -> "jamepad64.dll";
                case X86 -> "jamepad.dll";
                default -> null; // the jar has no Windows-on-ARM library
            };
        });
    }

    private static Os os(String name) {
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MAC;
        }
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("linux") || name.contains("nux")) {
            return Os.LINUX;
        }
        return null;
    }

    private static Cpu cpu(String arch) {
        return switch (arch) {
            case "aarch64", "arm64" -> Cpu.ARM64;
            case "amd64", "x86_64", "x64" -> Cpu.X64;
            case "x86", "i386", "i486", "i586", "i686" -> Cpu.X86;
            case "arm", "armv7", "armv7l", "armhf", "aarch32" -> Cpu.ARM32;
            default -> null;
        };
    }

    private NativeLibraryChooser() { }
}
