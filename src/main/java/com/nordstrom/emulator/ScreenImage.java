package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.system.HiResRenderer;
import com.nordstrom.emulator.system.LoResRenderer;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.ScanlineModes;
import com.nordstrom.emulator.system.TextScreenRenderer;
import com.nordstrom.emulator.system.VideoSoftSwitches;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The Apple's screen as an image, the way the main window shows it on a
 * given monitor: {@link #WIDTH} x {@link #HEIGHT}, the 280 x 192 display at
 * {@link #SCALE}. The window draws with this, and so do screenshots taken
 * without one -- the headless runner's {@code --snap}, and other projects'
 * documentation -- so a screenshot is what a person would see.
 * <p>
 * Each line is assembled as the 560 dots of the video signal, two per
 * emulated pixel, because a monochrome monitor shows detail finer than a
 * pixel: lo-res colors as dot patterns, and hi-res bytes with the palette
 * bit set a dot late. At scale 3 a dot is a screen pixel and a half wide,
 * so each screen column is drawn with the share of each dot it covers
 * ({@link #screenColumns}): a pair of equal dots stays a crisp 3-pixel
 * cell, and a lone dot stays where it really is. A color monitor shows each
 * pixel's decoded color across both its dots.
 */
public final class ScreenImage {

    /** Screen pixels per emulated pixel, across and down. */
    public static final int SCALE = 3;
    /** The image's width: 280 pixels at {@link #SCALE}. */
    public static final int WIDTH = TextScreenRenderer.COLUMNS * TextScreenRenderer.GLYPH_WIDTH * SCALE;
    /** The image's height: 192 lines at {@link #SCALE}. */
    public static final int HEIGHT = TextScreenRenderer.ROWS * TextScreenRenderer.GLYPH_HEIGHT * SCALE;

    private ScreenImage() {}

    /**
     * What the main window shows: the VideoTerm's 80 columns when the card
     * puts them there, else the Apple's own video -- text, lo-res, hi-res, or
     * a mid-frame mix -- line by line as the last frame showed it.
     *
     * @param bus               the machine
     * @param modes             each line's source in the last frame ({@link ScanlineModes#completedFrame})
     * @param switchedVideoTerm the VideoTerm the window switches to, or null for none
     * @param monitor           the monitor
     * @param flashVisible      which half of the flash cycle text shows
     * @return a new {@link #WIDTH} x {@link #HEIGHT} image
     */
    public static BufferedImage of(MotherboardBus bus, ScanlineModes.LineMode[] modes, VideoTerm switchedVideoTerm,
                                   MonitorType monitor, boolean flashVisible) {
        if (switchedVideoTerm != null && switchedVideoTerm.shownInMainWindow(bus.videoSoftSwitches())) {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                EightyColumnView.paint(g, switchedVideoTerm, WIDTH, HEIGHT, monitor.lit());
            } finally {
                g.dispose();
            }
            return image;
        }
        return appleVideo(bus, modes, monitor, flashVisible);
    }

    /**
     * Hi-res page 1, full screen, read from {@code memory} -- for a program
     * that keeps an Apple's memory itself (a test harness, say) rather than
     * running a {@link MotherboardBus}.
     *
     * @param memory  where to read the page from
     * @param monitor the monitor
     * @return a new {@link #WIDTH} x {@link #HEIGHT} image
     */
    public static BufferedImage hiRes(MemoryBus memory, MonitorType monitor) {
        VideoSoftSwitches page1 = new VideoSoftSwitches();
        boolean color = monitor.decodesColor();
        Color[][] colors = color ? HiResRenderer.render(memory, page1) : null;
        boolean[][] dots = color ? null : HiResRenderer.dots(memory, page1);
        int lit = monitor.lit().getRGB();
        int pixels = TextScreenRenderer.COLUMNS * TextScreenRenderer.GLYPH_WIDTH;
        int[] line = new int[pixels * 2];
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < HiResRenderer.LINES; row++) {
            for (int x = 0; x < pixels; x++) {
                if (color) {
                    setPair(line, x, colors[row][x].getRGB());
                } else {
                    line[2 * x] = dots[row][2 * x] ? lit : 0;
                    line[2 * x + 1] = dots[row][2 * x + 1] ? lit : 0;
                }
            }
            setLine(image, row, screenColumns(line, SCALE));
        }
        return image;
    }

    /**
     * Writes an image as a PNG file.
     *
     * @param image the image
     * @param file  where to write it
     * @throws IOException if it can't be written
     */
    public static void write(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("no PNG writer for " + file);
        }
    }

    private static BufferedImage appleVideo(MotherboardBus bus, ScanlineModes.LineMode[] modes,
                                            MonitorType monitor, boolean flashVisible) {
        boolean color = monitor.decodesColor();
        int lit = monitor.lit().getRGB();
        boolean[][] textPixels = TextScreenRenderer.render(bus, bus.videoSoftSwitches(), flashVisible);
        Color[][] loResColors = color ? LoResRenderer.render(bus, bus.videoSoftSwitches()) : null;
        boolean[][] loResDots = color ? null : LoResRenderer.dots(bus, bus.videoSoftSwitches());
        Color[][] hiResColors = color ? HiResRenderer.render(bus, bus.videoSoftSwitches()) : null;
        boolean[][] hiResDots = color ? null : HiResRenderer.dots(bus, bus.videoSoftSwitches());

        int pixelsPerLine = textPixels[0].length;
        int[] dots = new int[pixelsPerLine * 2];
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < textPixels.length; row++) {
            ScanlineModes.Source source = modes[row].source();
            for (int x = 0; x < pixelsPerLine; x++) {
                switch (source) {
                    case TEXT -> setPair(dots, x, textPixels[row][x] ? lit : 0);
                    case LORES -> {
                        if (color) {
                            setPair(dots, x, loResColors[row][x].getRGB());
                        } else {
                            dots[2 * x] = loResDots[row][2 * x] ? lit : 0;
                            dots[2 * x + 1] = loResDots[row][2 * x + 1] ? lit : 0;
                        }
                    }
                    case HIRES -> {
                        if (color) {
                            setPair(dots, x, hiResColors[row][x].getRGB());
                        } else {
                            dots[2 * x] = hiResDots[row][2 * x] ? lit : 0;
                            dots[2 * x + 1] = hiResDots[row][2 * x + 1] ? lit : 0;
                        }
                    }
                }
            }
            setLine(image, row, screenColumns(dots, SCALE));
        }
        return image;
    }

    /** Fills the {@link #SCALE} image lines of emulated line {@code row} with {@code columns}. */
    private static void setLine(BufferedImage image, int row, int[] columns) {
        for (int k = 0; k < SCALE; k++) {
            image.setRGB(0, row * SCALE + k, WIDTH, 1, columns, 0, WIDTH);
        }
    }

    private static void setPair(int[] dots, int pixel, int rgb) {
        dots[2 * pixel] = rgb;
        dots[2 * pixel + 1] = rgb;
    }

    /**
     * One line of dots as screen columns: each column's color is the mix of
     * the dots it covers, weighted by how much of the column each covers.
     *
     * @param dots  the line's dots, as RGB values, two per emulated pixel
     * @param scale screen pixels per emulated pixel
     * @return {@code dots.length / 2 * scale} RGB values
     */
    static int[] screenColumns(int[] dots, int scale) {
        // In half-columns, dot d spans [d*scale, (d+1)*scale) and column k spans [2k, 2k+2).
        int[] columns = new int[dots.length / 2 * scale];
        for (int k = 0; k < columns.length; k++) {
            int start = 2 * k;
            int end = start + 2;
            int r = 0;
            int gr = 0;
            int b = 0;
            for (int d = start / scale; d * scale < end; d++) {
                int share = Math.min(end, (d + 1) * scale) - Math.max(start, d * scale);
                r += share * ((dots[d] >> 16) & 0xFF);
                gr += share * ((dots[d] >> 8) & 0xFF);
                b += share * (dots[d] & 0xFF);
            }
            columns[k] = ((r / 2) << 16) | ((gr / 2) << 8) | (b / 2);
        }
        return columns;
    }
}
