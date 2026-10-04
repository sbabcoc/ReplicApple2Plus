package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditMenuImageTest {

    /** Paints a known pattern the way ScreenPanel does: black background, colored cells. */
    private static final class PatternPanel extends JPanel {
        PatternPanel() {
            setBackground(Color.BLACK);
            setSize(30, 20);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Color.GREEN);
            g.fillRect(3, 6, 3, 3); // one 3x3 "pixel", like SCALE = 3
        }
    }

    @Test
    void snapshotCapturesExactlyWhatTheComponentPaintsAtItsOnScreenSize() {
        BufferedImage image = EditMenu.snapshot(new PatternPanel());
        assertEquals(30, image.getWidth());
        assertEquals(20, image.getHeight());
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 30; x++) {
                boolean inCell = x >= 3 && x < 6 && y >= 6 && y < 9;
                int expected = (inCell ? Color.GREEN : Color.BLACK).getRGB() & 0xFFFFFF;
                assertEquals(expected, image.getRGB(x, y) & 0xFFFFFF, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void imageSelectionOffersOnlyTheImageFlavor() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ImageSelection selection = new ImageSelection(image);
        assertTrue(selection.isDataFlavorSupported(DataFlavor.imageFlavor));
        assertFalse(selection.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertSame(image, selection.getTransferData(DataFlavor.imageFlavor));
        assertThrows(UnsupportedFlavorException.class, () -> selection.getTransferData(DataFlavor.stringFlavor));
    }
}
