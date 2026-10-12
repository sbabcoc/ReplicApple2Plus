package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the {@code --plugins} path form: several directories in one value,
 * separated by the platform path separator, each contributing its jars.
 * <p>
 * Each test closes the loader it gets: a {@link URLClassLoader} keeps the
 * jars it has read open, and Windows won't delete an open file -- so an
 * unclosed loader makes the {@code @TempDir} cleanup fail there.
 */
class PluginLoaderTest {

    @Test
    void loadsJarsFromEveryDirectoryInThePath(@TempDir Path root) throws IOException {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        jarWith(first.resolve("a.jar"), "only-in-first.txt", "first");
        jarWith(second.resolve("b.jar"), "only-in-second.txt", "second");

        try (URLClassLoader loader = (URLClassLoader) PluginLoader.load(first + File.pathSeparator + second,
                ClassLoader.getPlatformClassLoader())) {
            assertEquals("first", read(loader, "only-in-first.txt"));
            assertEquals("second", read(loader, "only-in-second.txt"));
        }
    }

    @Test
    void earlierDirectoriesWinWhenTwoSupplyTheSameResource(@TempDir Path root) throws IOException {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        jarWith(first.resolve("a.jar"), "shared.txt", "first");
        jarWith(second.resolve("b.jar"), "shared.txt", "second");

        try (URLClassLoader loader = (URLClassLoader) PluginLoader.load(first + File.pathSeparator + second,
                ClassLoader.getPlatformClassLoader())) {
            assertEquals("first", read(loader, "shared.txt"));
        }
    }

    @Test
    void skipsEmptyEntriesAndMissingDirectories(@TempDir Path root) throws IOException {
        Path present = Files.createDirectory(root.resolve("present"));
        jarWith(present.resolve("a.jar"), "found.txt", "found");
        String path = File.pathSeparator + root.resolve("missing") + File.pathSeparator + File.pathSeparator
                + present + File.pathSeparator;

        try (URLClassLoader loader = (URLClassLoader) PluginLoader.load(path, ClassLoader.getPlatformClassLoader())) {
            assertEquals("found", read(loader, "found.txt"));
        }
    }

    @Test
    void aPathOfOnlyMissingDirectoriesLeavesTheParentAsIs(@TempDir Path root) throws IOException {
        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        String path = root.resolve("missing1") + File.pathSeparator + root.resolve("missing2");

        assertSame(parent, PluginLoader.load(path, parent));
    }

    private static void jarWith(Path jar, String entryName, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry(entryName));
            jarOut.write(content.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }

    private static String read(ClassLoader loader, String name) throws IOException {
        try (InputStream in = loader.getResourceAsStream(name)) {
            assertNotNull(in, name + " not found through the plugin class loader");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
