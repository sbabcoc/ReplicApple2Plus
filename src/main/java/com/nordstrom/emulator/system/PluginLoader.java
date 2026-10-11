package com.nordstrom.emulator.system;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds a {@link ClassLoader} that adds every {@code .jar} file in one or
 * more plugins directories to the classpath, on top of the normal application
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
     *
     * @param pluginsDirectory directory to scan for {@code .jar} files
     * @param parent the classloader to fall back to for everything else
     * @return a classloader covering both {@code parent} and any jars found
     * @throws IOException if {@code pluginsDirectory} can't be scanned
     */
    public static ClassLoader load(Path pluginsDirectory, ClassLoader parent) throws IOException {
        return load(List.of(pluginsDirectory), parent);
    }

    /**
     * Returns a {@link ClassLoader} covering every {@code .jar} in every
     * directory of {@code pluginsPath}, a list of directories separated by
     * the platform's path separator ({@link File#pathSeparator}: {@code :}
     * on macOS and Linux, {@code ;} on Windows), the same form as a class
     * path. Directories are searched in the order given, so if two of them
     * supply the same class, the earlier one wins. Empty entries and
     * directories that don't exist are skipped, as in
     * {@link #load(Path, ClassLoader)}.
     *
     * @param pluginsPath directories to scan for {@code .jar} files
     * @param parent the classloader to fall back to for everything else
     * @return a classloader covering both {@code parent} and any jars found
     * @throws IOException if a directory in {@code pluginsPath} can't be scanned
     */
    public static ClassLoader load(String pluginsPath, ClassLoader parent) throws IOException {
        List<Path> directories = new ArrayList<>();
        for (String entry : pluginsPath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isEmpty()) {
                directories.add(Path.of(entry));
            }
        }
        return load(directories, parent);
    }

    private static ClassLoader load(List<Path> pluginsDirectories, ClassLoader parent) throws IOException {
        List<URL> jarUrls = new ArrayList<>();
        boolean anyDirectory = false;
        for (Path directory : pluginsDirectories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            anyDirectory = true;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : stream) {
                    jarUrls.add(toUrl(jar));
                }
            }
        }
        return anyDirectory ? new URLClassLoader(jarUrls.toArray(new URL[0]), parent) : parent;
    }

    /** Converts a jar's {@link Path} to the {@link URL} form {@link URLClassLoader} needs. */
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
