package com.nordstrom.emulator;

import com.nordstrom.emulator.system.LoResRenderer;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.ScanlineModes;
import com.nordstrom.emulator.system.TextScreenRenderer;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Paints the current screen -- text, lo-res, or a real mid-frame mix of
 * both -- scaled up from the real 280x192 pixel display to something
 * visible on a modern screen. Pure Swing glue -- all the actual
 * rendering logic (addressing, glyphs, colors) lives in
 * {@link TextScreenRenderer} and {@link LoResRenderer}, which this
 * class has no opinion about beyond calling them and drawing the
 * result; which renderer's data applies to which of the 192 rows comes
 * from {@link ScanlineModes}, which this class also has no opinion
 * about beyond reading it.
 * <p>
 * Deliberately renders BOTH the full text screen and the full lo-res
 * screen every single paint, then picks per-row which one's data to
 * actually use, rather than trying to render only the "active" one --
 * simpler and more robust than threading a per-row "which renderer"
 * decision into either renderer's own internals, and cheap enough
 * (at most a few hundred thousand pixel lookups, a handful of times a
 * second) that the wasted work from rendering the "wrong" mode's data
 * for a given row is not worth avoiding.
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
    private final ScanlineModes scanlineModes;
    private boolean flashVisible = true;

    ScreenPanel(MotherboardBus bus, ScanlineModes scanlineModes) {
        this.bus = bus;
        this.scanlineModes = scanlineModes;
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
        boolean[][] textPixels = TextScreenRenderer.render(bus, bus.videoSoftSwitches(), flashVisible);
        Color[][] loResPixels = LoResRenderer.render(bus, bus.videoSoftSwitches());
        ScanlineModes.LineMode[] modes = scanlineModes.completedFrame();

        for (int row = 0; row < textPixels.length; row++) {
            ScanlineModes.Source source = modes[row].source();
            for (int col = 0; col < textPixels[row].length; col++) {
                Color color = switch (source) {
                    case TEXT -> textPixels[row][col] ? Color.GREEN : Color.BLACK;
                    case LORES -> loResPixels[row][col];
                    case HIRES -> Color.BLACK; // not yet implemented -- see HARDWARE-REFERENCE.md
                };
                if (!color.equals(Color.BLACK)) {
                    g.setColor(color);
                    g.fillRect(col * SCALE, row * SCALE, SCALE, SCALE);
                }
            }
        }
    }
}
