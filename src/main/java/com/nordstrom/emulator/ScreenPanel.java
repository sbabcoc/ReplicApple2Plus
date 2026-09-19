package com.nordstrom.emulator;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.TextScreenRenderer;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Paints the current 40x24 text screen, scaled up from the real 280x192
 * pixel display to something visible on a modern screen. Pure Swing
 * glue -- all the actual rendering logic (addressing, glyphs,
 * inverse/flash) lives in {@link TextScreenRenderer}, which this class
 * has no opinion about beyond calling it and drawing the result.
 * <p>
 * Flash state (which characters in the flash range currently show
 * inverted) is owned by {@link Apple2Plus}, not this panel -- flash is
 * a real-time visual effect tied to wall-clock time, the same
 * separation of concerns {@link TextScreenRenderer} itself documents,
 * so whoever drives real time (the application's own timer loop) is
 * the natural owner of it, not the thing being painted.
 */
final class ScreenPanel extends JPanel {

    /** How many real screen pixels each emulated pixel occupies. */
    static final int SCALE = 3;

    private static final int WIDTH = TextScreenRenderer.COLUMNS * TextScreenRenderer.GLYPH_WIDTH * SCALE;
    private static final int HEIGHT = TextScreenRenderer.ROWS * TextScreenRenderer.GLYPH_HEIGHT * SCALE;

    private final MotherboardBus bus;
    private boolean flashVisible = true;

    ScreenPanel(MotherboardBus bus) {
        this.bus = bus;
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        boolean[][] pixels = TextScreenRenderer.render(bus, bus.videoSoftSwitches(), flashVisible);
        g.setColor(Color.GREEN);
        for (int row = 0; row < pixels.length; row++) {
            for (int col = 0; col < pixels[row].length; col++) {
                if (pixels[row][col]) {
                    g.fillRect(col * SCALE, row * SCALE, SCALE, SCALE);
                }
            }
        }
    }
}
