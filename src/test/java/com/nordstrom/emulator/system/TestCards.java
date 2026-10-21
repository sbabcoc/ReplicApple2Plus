package com.nordstrom.emulator.system;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Cards for testing discovery and configuration, registered only in a
 * class loader made by {@link #loader} -- never in the project's own
 * service file, so the real application never sees them.
 */
final class TestCards {

    private TestCards() {}

    /**
     * A class loader over the project's own cards plus a service file
     * listing {@code providers}, written to {@code dir}.
     */
    static ClassLoader loader(Path dir, String... providers) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.write(services.resolve(SlotCard.class.getName()), List.of(providers));
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, TestCards.class.getClassLoader());
    }

    /** The parts of a card that don't matter to these tests. */
    abstract static class Base implements SlotCard {
        @Override
        public List<CardParameter> getParameters() {
            return List.of();
        }

        @Override
        public void configure(Properties props) {
        }

        @Override
        public int readIoSwitch(int offset) {
            return 0;
        }

        @Override
        public void writeIoSwitch(int offset, int value) {
        }

        @Override
        public boolean hasRom() {
            return false;
        }

        @Override
        public int readRom(int offset) {
            return 0;
        }
    }

    /** A card with one whole-number setting, which it reads. */
    public static final class CountCard extends Base {
        int count = 1;

        @Override
        public String getShortName() {
            return "testCount";
        }

        @Override
        public String getDisplayName() {
            return "Count Card";
        }

        @Override
        public List<CardParameter> getParameters() {
            return List.of(CardParameter.integer("count", "Count", 1, 1, 10));
        }

        @Override
        public void configure(Properties props) {
            count = Integer.parseInt(props.getProperty("count", "1"));
        }
    }

    /** A card that reads a setting it doesn't declare. */
    public static final class SneakyCard extends Base {
        @Override
        public String getShortName() {
            return "testSneaky";
        }

        @Override
        public String getDisplayName() {
            return "Sneaky Card";
        }

        @Override
        public void configure(Properties props) {
            props.getProperty("undeclared");
        }
    }

    /** One of two different cards claiming the same short name. */
    public static final class ClashA extends Base {
        @Override
        public String getShortName() {
            return "testClash";
        }

        @Override
        public String getDisplayName() {
            return "Clash A";
        }
    }

    /** The other card claiming that short name. */
    public static final class ClashB extends Base {
        @Override
        public String getShortName() {
            return "testClash";
        }

        @Override
        public String getDisplayName() {
            return "Clash B";
        }
    }

    /** A card whose constructor fails. */
    public static final class ThrowingCard extends Base {
        public ThrowingCard() {
            throw new IllegalStateException("broken constructor");
        }

        @Override
        public String getShortName() {
            return "testThrowing";
        }

        @Override
        public String getDisplayName() {
            return "Throwing Card";
        }
    }

    /** A card with a blank display name. */
    public static final class NamelessCard extends Base {
        @Override
        public String getShortName() {
            return "testNameless";
        }

        @Override
        public String getDisplayName() {
            return " ";
        }
    }

    /** A card listing one setting twice. */
    public static final class DoubledCard extends Base {
        @Override
        public String getShortName() {
            return "testDoubled";
        }

        @Override
        public String getDisplayName() {
            return "Doubled Card";
        }

        @Override
        public List<CardParameter> getParameters() {
            return List.of(CardParameter.folder("dir", "Folder"), CardParameter.folder("dir", "Folder again"));
        }
    }
}
