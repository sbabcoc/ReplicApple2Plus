package com.nordstrom.emulator.input;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import com.nordstrom.emulator.system.PluginLoader;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Jamepad is not a dependency of this project, so most of these run
 * only when its jar happens to be on the class path, and are skipped
 * otherwise. The one that does not need it checks the behavior users
 * without the jar actually see.
 */
class JamepadProviderTest {

    private static boolean jamepadPresent() {
        try {
            Class.forName("com.studiohartman.jamepad.ControllerManager");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Test
    void withoutTheJarSupportIsQuietlyOffAndTheReasonExplainsHowToEnableIt() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            PadProvider provider = JamepadProvider.create(empty, new PrintStream(bytes, true));
            assertNull(provider);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        String message = bytes.toString();
        assertTrue(message.contains("--plugins") && message.contains("jamepad"), message);
    }

    @Test
    void everyLibraryTheChooserCanNameIsActuallyInTheJar() {
        assumeTrue(jamepadPresent(), "Jamepad jar not on the class path");
        ClassLoader loader = JamepadProvider.class.getClassLoader();
        List<String[]> platforms = List.of(
            new String[]{"Mac OS X", "aarch64"}, new String[]{"Mac OS X", "x86_64"},
            new String[]{"Linux", "amd64"}, new String[]{"Linux", "aarch64"},
            new String[]{"Linux", "arm"}, new String[]{"Linux", "x86"},
            new String[]{"Windows 11", "amd64"}, new String[]{"Windows 10", "x86"});
        for (String[] p : platforms) {
            String name = NativeLibraryChooser.choose(p[0], p[1]).orElseThrow();
            assertNotNull(loader.getResource(name), name + " (" + p[0] + ", " + p[1] + ") is missing from the jar");
        }
    }

    @Test
    void startsCleanlyAndPollsWithoutAControllerAndItsStartupNoiseIsHeldBack() {
        assumeTrue(jamepadPresent(), "Jamepad jar not on the class path");
        assumeTrue(NativeLibraryChooser.choose(System.getProperty("os.name"), System.getProperty("os.arch")).isPresent(),
            "no native library for this platform");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
        PadProvider provider;
        try {
            System.setErr(new PrintStream(errBytes, true));
            provider = JamepadProvider.create(JamepadProvider.class.getClassLoader(), new PrintStream(logBytes, true));
        } finally {
            System.setErr(originalErr);
        }

        assertNotNull(provider, "should start; the log said: " + logBytes);
        try {
            assertFalse(errBytes.toString().contains("gamecontrollerdb"),
                "Jamepad's harmless missing-database stack trace should not reach the user: " + errBytes);
            PadSnapshot snapshot = provider.poll();
            assertNotNull(snapshot);
            assertFalse(provider.description().isEmpty());
            if (provider.description().equals("no gamepad connected")) {
                assertEquals(PadSnapshot.NEUTRAL, snapshot);
            }
        } finally {
            provider.close();
        }
    }

    /**
     * The regression test for a real failure: JNI binds a native library
     * to the class loader that loaded it, so a library loaded by this
     * project's own loader is invisible to Jamepad's classes when they
     * were defined by a different loader -- which is exactly what
     * happens when the jar is supplied through {@code --plugins}. It
     * showed up as an UnsatisfiedLinkError only in that setup; setups
     * with the jar on the class path hid it, because there everything
     * shares one loader. So this test makes the plugins directory the
     * ONLY way the jar can be seen.
     */
    @Test
    void worksWhenTheJarIsOnlyInAPluginsDirectoryNotOnTheClassPath(@TempDir Path pluginsDir) throws Exception {
        assumeTrue(jamepadPresent(), "Jamepad jar not on the class path");
        String where = JamepadProvider.class.getClassLoader()
            .getResource("com/studiohartman/jamepad/ControllerManager.class").toString();
        assumeTrue(where.startsWith("jar:file:"), "Jamepad is not on the class path as a jar file");
        Path jar = Path.of(new URI(where.substring("jar:".length(), where.lastIndexOf("!/"))));
        Files.copy(jar, pluginsDir.resolve("jamepad.jar"));

        // The platform loader cannot see Jamepad, so only the plugins directory can supply it.
        ClassLoader onlyPlugins = PluginLoader.load(pluginsDir, ClassLoader.getPlatformClassLoader());
        try {
            Class.forName("com.studiohartman.jamepad.ControllerManager", false, ClassLoader.getPlatformClassLoader());
            throw new AssertionError("test setup is wrong: the platform loader can see Jamepad");
        } catch (ClassNotFoundException expected) {
            // good: the plugins directory is the only route
        }

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PadProvider provider = JamepadProvider.create(onlyPlugins, new PrintStream(log, true));
        assertNotNull(provider, "should start from a plugins directory; the log said: " + log);
        try {
            assertNotNull(provider.poll());
        } finally {
            provider.close();
        }
    }
}
