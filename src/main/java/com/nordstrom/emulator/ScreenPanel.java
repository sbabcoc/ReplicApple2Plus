package com.nordstrom.emulator;

import com.nordstrom.emulator.system.HiResRenderer;
import com.nordstrom.emulator.system.LoResRenderer;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.system.ScanlineModes;
import com.nordstrom.emulator.system.TextScreenRenderer;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Paints the current screen -- text, lo-res, or a real mid-frame mix of
 * both -- scaled up from the real 280x192 pixel display to something
 * visible on a modern screen. Pure Swing glue -- all the actual
 * rendering logic (addressing, glyphs, colors) lives in
 * {@link TextScreenRenderer}, {@link LoResRenderer} and {@link HiResRenderer}, which this
 * class has no opinion about beyond calling them and drawing the
 * result; which renderer's data applies to which of the 192 rows comes
 * from {@link ScanlineModes}, which this class also has no opinion
 * about beyond reading it.
 * <p>
 * Deliberately renders the full text screen, the full lo-res screen,
 * AND the full hi-res screen every single paint, then picks per-row
 * which one's data to actually use, rather than trying to render only
 * the "active" one -- simpler and more robust than threading a per-row
 * "which renderer" decision into any renderer's own internals, and
 * cheap enough (well under a million pixel lookups, a handful of times
 * a second) that the wasted work from rendering the "wrong" mode's data
 * for a given row is not worth avoiding.
 * <p>
 * Flash state (which characters in the flash range currently show
 * inverted) is owned by {@link Apple2Plus}, not this panel -- flash is
 * a real-time visual effect tied to wall-clock time, the same
 * separation of concerns {@link TextScreenRenderer} itself documents,
 * so whoever drives real time (the application's own timer loop) is
 * the natural owner of it, not the thing being painted.
 * <p>
 * Each line is assembled as the 560 dots of the video signal, two per
 * emulated pixel, because a monochrome monitor shows lo-res colors as dot
 * patterns finer than a pixel. At {@link #SCALE} 3 a dot is a screen pixel
 * and a half wide, so each screen column is drawn with the share of each dot
 * it covers ({@link #screenColumns}): a pair of equal dots stays a crisp
 * 3-pixel cell, and a lone dot stays where it really is.
 */
final class ScreenPanel extends JPanel {

    /** How many real screen pixels each emulated pixel occupies. */
    static final int SCALE = 3;

    private static final int WIDTH = TextScreenRenderer.COLUMNS * TextScreenRenderer.GLYPH_WIDTH * SCALE;
    private static final int HEIGHT = TextScreenRenderer.ROWS * TextScreenRenderer.GLYPH_HEIGHT * SCALE;

    private final MotherboardBus bus;
    private final ScanlineModes scanlineModes;
    private final VideoTerm switchedVideoTerm;
    private boolean flashVisible = true;
    private MonitorType monitor = MonitorType.COLOR;

    ScreenPanel(MotherboardBus bus, ScanlineModes scanlineModes) {
        this(bus, scanlineModes, null);
    }

    /**
     * @param switchedVideoTerm a VideoTerm whose output this panel shows in
     *                          place of the Apple's own video whenever the
     *                          card's display mode puts 80 columns in the main
     *                          window (always, or when the Soft Video Switch
     *                          selects them), or null for none
     */
    ScreenPanel(MotherboardBus bus, ScanlineModes scanlineModes, VideoTerm switchedVideoTerm) {
        this.bus = bus;
        this.scanlineModes = scanlineModes;
        this.switchedVideoTerm = switchedVideoTerm;
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
    }

    /**
     * Toggles which half of the flash cycle is currently showing.
     * Called periodically (roughly twice a second) by whatever drives
     * real time for this application.
     */
    void toggleFlash() {
        flashVisible = !flashVisible;
    }

    /** @param monitor the kind of monitor this panel shows the video on, from the next paint */
    void setMonitor(MonitorType monitor) {
        this.monitor = monitor;
    }

    /** @return the VideoTerm this panel switches to, or null if none */
    VideoTerm switchedVideoTerm() {
        return switchedVideoTerm;
    }

    /** @return true when this panel is currently showing the VideoTerm's 80 columns rather than the Apple's video */
    boolean showingEightyColumns() {
        if (switchedVideoTerm == null) {
            return false;
        }
        return switch (switchedVideoTerm.display()) {
            case SLOT3 -> true;
            case SWITCHED -> bus.videoSoftSwitches().softVideoSwitchSelects80Columns();
            case APPLE, SEPARATE -> false;
        };
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (showingEightyColumns()) {
            EightyColumnView.paint(g, switchedVideoTerm, getWidth(), getHeight(), monitor.lit());
            return;
        }
        boolean color = monitor.decodesColor();
        int lit = monitor.lit().getRGB();
        boolean[][] textPixels = TextScreenRenderer.render(bus, bus.videoSoftSwitches(), flashVisible);
        Color[][] loResColors = color ? LoResRenderer.render(bus, bus.videoSoftSwitches()) : null;
        boolean[][] loResDots = color ? null : LoResRenderer.dots(bus, bus.videoSoftSwitches());
        Color[][] hiResColors = color ? HiResRenderer.render(bus, bus.videoSoftSwitches()) : null;
        boolean[][] hiResPixels = color ? null : HiResRenderer.monochrome(bus, bus.videoSoftSwitches());
        ScanlineModes.LineMode[] modes = scanlineModes.completedFrame();

        int pixelsPerLine = textPixels[0].length;
        int[] dots = new int[pixelsPerLine * 2];
        BufferedImage image = new BufferedImage(pixelsPerLine * SCALE, textPixels.length, BufferedImage.TYPE_INT_RGB);
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
                    case HIRES -> setPair(dots, x, color ? hiResColors[row][x].getRGB() : hiResPixels[row][x] ? lit : 0);
                }
            }
            image.setRGB(0, row, image.getWidth(), 1, screenColumns(dots, SCALE), 0, image.getWidth());
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(image, 0, 0, image.getWidth(), image.getHeight() * SCALE, null);
        } finally {
            g2.dispose();
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
