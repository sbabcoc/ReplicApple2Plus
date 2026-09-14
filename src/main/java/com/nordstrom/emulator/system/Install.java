package com.nordstrom.emulator.system;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A simple installer for a single peripheral jar -- built-in or
 * third-party, no distinction. Any {@code SlotCard} jar can declare this
 * class as its own {@code Main-Class}; running
 * {@code java -jar SomePeripheral.jar [pluginsDirectory]} copies that
 * same jar into the given directory (default {@code plugins}, relative
 * to the current directory, if none given), ready for
 * {@link PluginLoader} to discover it on the next boot. Nothing more:
 * no bundled build files, no wrapper, no project scaffolding -- getting
 * one jar into one folder is the entire job.
 */
public final class Install {

    public static void main(String[] args) throws IOException, URISyntaxException {
        Path pluginsDir = Path.of(args.length > 0 ? args[0] : "plugins");
        Files.createDirectories(pluginsDir);

        Path selfJar = Path.of(Install.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = pluginsDir.resolve(selfJar.getFileName());

        Files.copy(selfJar, target, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Installed " + selfJar.getFileName() + " to " + target.toAbsolutePath());
    }

    private Install() {}
}
