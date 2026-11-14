package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.expansion.VideoTermRenderer;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Shows a VideoTerm's 80-column output: the second window in
 * {@link VideoTerm.Display#SEPARATE} mode, and -- through {@link #paint(Graphics, VideoTerm, int, int, Color)}
 * -- the main window's 80-column view in {@link VideoTerm.Display#SWITCHED} mode.
 * <p>
 * The card's 640 x 216 dots are stretched to fill whatever area they're
 * given, the way a monitor fills its screen with whatever signal it gets;
 * nearest-neighbor scaling keeps the dots crisp.
 */
final class EightyColumnView extends JPanel {

    private final VideoTerm card;
    private MonitorType monitor = MonitorType.GREEN;

    EightyColumnView(VideoTerm card, Dimension size) {
        this.card = card;
        setPreferredSize(size);
        setBackground(Color.BLACK);
    }

    VideoTerm card() {
        return card;
    }

    /** @param monitor the kind of monitor this window shows the card's output on, from the next paint */
    void setMonitor(MonitorType monitor) {
        this.monitor = monitor;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        paint(g, card, getWidth(), getHeight(), monitor.lit());
    }

    /**
     * Draws the card's current screen into a {@code width} x {@code height} area.
     *
     * @param g      where to draw
     * @param card   the card to show
     * @param width  the area's width
     * @param height the area's height
     * @param lit    the color of a lit dot
     */
    static void paint(Graphics g, VideoTerm card, int width, int height, Color lit) {
        boolean phase = VideoTermRenderer.cursorBlinkPhase(card.register(10), System.nanoTime());
        boolean[][] dots = VideoTermRenderer.render(card, phase);
        if (dots.length == 0 || dots[0].length == 0) {
            return; // CRTC not set up yet: nothing displayed
        }
        BufferedImage image = new BufferedImage(dots[0].length, dots.length, BufferedImage.TYPE_INT_RGB);
        int litRgb = lit.getRGB();
        for (int y = 0; y < dots.length; y++) {
            for (int x = 0; x < dots[y].length; x++) {
                if (dots[y][x]) {
                    image.setRGB(x, y, litRgb);
                }
            }
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(image, 0, 0, width, height, null);
        } finally {
            g2.dispose();
        }
    }
}
