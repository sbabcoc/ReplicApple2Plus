package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ScreenImage's own entry points: hi-res page 1 from a program's memory, the monitors by name, and PNG output. */
class ScreenImageTest {

    @TempDir
    Path folder;

    /** 64K of memory, as a test harness keeps it. */
    private static final class Ram implements MemoryBus {
        final int[] bytes = new int[0x10000];

        @Override
        public int read(int address) {
            return bytes[address];
        }

        @Override
        public void write(int address, int value) {
            bytes[address] = value & 0xFF;
        }
    }

    @Test
    void aLitPixelOnAMonochromeMonitorIsACrispThreeByThreeCellInThePhosphorsColor() {
        Ram ram = new Ram();
        ram.bytes[0x2000] = 0x01;                     // line 0, pixel 0
        BufferedImage image = ScreenImage.hiRes(ram, MonitorType.GREEN);
        assertEquals(ScreenImage.WIDTH, image.getWidth());
        assertEquals(ScreenImage.HEIGHT, image.getHeight());
        int green = MonitorType.GREEN.lit().getRGB() & 0xFFFFFF;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int expected = x < 3 && y < 3 ? green : 0;
                assertEquals(expected, image.getRGB(x, y) & 0xFFFFFF, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void thePaletteBitShiftsAByteHalfAPixelOnAMonochromeMonitor() {
        Ram ram = new Ram();
        ram.bytes[0x2000] = 0x81;                     // pixel 0, a dot late
        BufferedImage image = ScreenImage.hiRes(ram, MonitorType.AMBER);
        int amber = MonitorType.AMBER.lit().getRGB() & 0xFFFFFF;
        int half = ((amber >> 16 & 0xFF) / 2) << 16 | ((amber >> 8 & 0xFF) / 2) << 8 | (amber & 0xFF) / 2;
        // dots 1 and 2 lit, each 1.5 columns wide: columns 1-4 of the 840
        int[] expected = {0, half, amber, amber, half, 0};
        for (int x = 0; x < expected.length; x++) {
            assertEquals(expected[x], image.getRGB(x, 0) & 0xFFFFFF, "column " + x);
        }
    }

    @Test
    void readsPageOneOnly() {
        Ram ram = new Ram();
        ram.bytes[0x4000] = 0x7F;                     // page 2
        BufferedImage image = ScreenImage.hiRes(ram, MonitorType.WHITE);
        for (int x = 0; x < ScreenImage.WIDTH; x++) {
            assertEquals(0, image.getRGB(x, 0) & 0xFFFFFF);
        }
    }

    @Test
    void aColorMonitorShowsTheDecodedColor() {
        Ram ram = new Ram();
        ram.bytes[0x2000] = 0x7F;                     // seven pixels on: white
        BufferedImage image = ScreenImage.hiRes(ram, MonitorType.COLOR);
        assertEquals(0xFFFFFF, image.getRGB(9, 1) & 0xFFFFFF);
    }

    @Test
    void monitorsAreNamedInAnyCase() {
        assertEquals(MonitorType.GREEN, MonitorType.named("green"));
        assertEquals(MonitorType.AMBER, MonitorType.named("AMBER"));
        assertEquals(MonitorType.COLOR, MonitorType.named("Color"));
        assertThrows(IllegalArgumentException.class, () -> MonitorType.named("sepia"));
    }

    @Test
    void writesAPngThatReadsBackTheSame() throws Exception {
        Ram ram = new Ram();
        ram.bytes[0x2000] = 0x55;
        BufferedImage image = ScreenImage.hiRes(ram, MonitorType.WHITE);
        Path file = folder.resolve("screen.png");
        ScreenImage.write(image, file);
        BufferedImage back = ImageIO.read(file.toFile());
        assertEquals(image.getWidth(), back.getWidth());
        for (int x = 0; x < 30; x++) {
            assertEquals(image.getRGB(x, 0), back.getRGB(x, 0), "column " + x);
        }
    }
}
