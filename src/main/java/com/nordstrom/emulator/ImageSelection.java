package com.nordstrom.emulator;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * An image on the clipboard -- the image counterpart of AWT's own
 * {@link java.awt.datatransfer.StringSelection}, which the JDK doesn't
 * provide. The platform converts it to its native clipboard image format
 * (TIFF/PNG on macOS) for other applications.
 */
final class ImageSelection implements Transferable {

    private final Image image;

    ImageSelection(Image image) {
        this.image = image;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] {DataFlavor.imageFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return DataFlavor.imageFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return image;
    }
}
