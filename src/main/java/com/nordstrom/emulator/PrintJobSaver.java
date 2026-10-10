package com.nordstrom.emulator;

import com.nordstrom.emulator.transfer.PrintSpool;
import com.nordstrom.emulator.transfer.PrintText;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * The host side of printing to the transfer card (PR#n): watches the card's
 * print spool and, once printing has been idle for {@link #IDLE_SECONDS},
 * takes the job, converts it to host text and asks where to save it
 * (TRANSFER-CARD.md 7). Anything printed while the dialog is open goes into
 * the next job. Runs entirely on the Swing event thread.
 */
final class PrintJobSaver {

    /** How long printing must stop before a job counts as finished. */
    static final double IDLE_SECONDS = 1.5;

    private static final Preferences PREFS = Preferences.userNodeForPackage(PrintJobSaver.class);
    private static final String DIRECTORY_KEY = "printDirectory";

    private final JFrame owner;
    private final PrintSpool spool;
    private final Path initialDirectory;
    private final Timer timer;
    private boolean asking;

    /**
     * @param owner            the window the dialog belongs to; may be null
     * @param spool            the card's print spool
     * @param initialDirectory where the dialog opens if nothing is remembered; may be null
     */
    PrintJobSaver(JFrame owner, PrintSpool spool, Path initialDirectory) {
        this.owner = owner;
        this.spool = spool;
        this.initialDirectory = initialDirectory;
        this.timer = new Timer(250, e -> check());
        timer.start();
    }

    /** Stops watching, e.g. when the machine it belongs to is replaced. */
    void dispose() {
        timer.stop();
    }

    private void check() {
        if (asking) {
            return; // one dialog at a time; further output waits in the spool
        }
        byte[] job = spool.takeIfIdle((long) (IDLE_SECONDS * TimeUnit.SECONDS.toNanos(1)));
        if (job != null) {
            asking = true;
            try {
                save(PrintText.toHost(job));
            } finally {
                asking = false;
            }
        }
    }

    private void save(byte[] text) {
        JFileChooser chooser = new JFileChooser(startDirectory());
        chooser.setDialogTitle(String.format("Save Printed Output (%,d characters)", text.length));
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), "printout.txt"));
        while (chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file.exists() && JOptionPane.showConfirmDialog(owner, file.getName() + " already exists. Replace it?",
                    "Save Printed Output", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                continue; // back to the dialog
            }
            try {
                Files.write(file.toPath(), text);
                PREFS.put(DIRECTORY_KEY, file.getParentFile().getAbsolutePath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(owner, "Couldn't save " + file + ": " + e.getMessage(),
                    "Save Printed Output", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            return;
        }
        // cancelled: the job is discarded, as on a printer with no paper
    }

    private File startDirectory() {
        String last = PREFS.get(DIRECTORY_KEY, null);
        if (last != null && new File(last).isDirectory()) {
            return new File(last);
        }
        return initialDirectory != null ? initialDirectory.toFile() : null;
    }
}
