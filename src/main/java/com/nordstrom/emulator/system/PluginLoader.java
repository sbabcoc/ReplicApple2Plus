package com.nordstrom.emulator.system;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link ClassLoader} that adds every {@code .jar} file in a
 * plugins directory to the classpath, on top of the normal application
 * classpath -- the actual mechanism that lets a new peripheral card be
 * added to a running ReplicApple2Plus without recompiling or even
 * rebuilding it.
 * <p>
 * Each jar is expected to carry its own
 * {@code META-INF/services/com.nordstrom.emulator.system.SlotCard} entry
 * naming its own {@code SlotCard} implementations -- an entirely ordinary
 * Java SPI plugin jar, no project-specific packaging convention beyond
 * that. The {@link ClassLoader} this returns is meant to be passed
 * straight into {@link CardTypes#create}, whose real
 * {@link java.util.ServiceLoader}-based discovery will then see both
 * this project's own built-in cards AND every plugin jar's cards
 * uniformly, through the same mechanism.
 */
public final class PluginLoader {

    /**
     * Returns a {@link ClassLoader} covering every {@code .jar} in
     * {@code pluginsDirectory}, parented by {@code parent} for everything
     * else (this project's own classes, the JDK, etc.). If the directory
     * doesn't exist, returns {@code parent} unchanged -- an absent
     * plugins directory is not an error, it just means no plugins.
     */
    public static ClassLoader load(Path pluginsDirectory, ClassLoader parent) throws IOException {
        if (!Files.isDirectory(pluginsDirectory)) {
            return parent;
        }
        List<URL> jarUrls = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
            for (Path jar : stream) {
                jarUrls.add(toUrl(jar));
            }
        }
        return new URLClassLoader(jarUrls.toArray(new URL[0]), parent);
    }

    private static URL toUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            // A Path's own toUri().toURL() failing would mean something is
            // fundamentally wrong with the JVM's own URL handling, not with
            // any particular plugin jar -- this should never actually happen.
            throw new IllegalStateException("Could not convert " + jar + " to a URL", e);
        }
    }

    private PluginLoader() {}
}
