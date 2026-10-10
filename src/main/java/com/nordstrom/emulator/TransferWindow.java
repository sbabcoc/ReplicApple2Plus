package com.nordstrom.emulator;

import com.nordstrom.emulator.transfer.Capabilities;
import com.nordstrom.emulator.transfer.GuestEntry;
import com.nordstrom.emulator.transfer.GuestNames;
import com.nordstrom.emulator.transfer.HostNames;
import com.nordstrom.emulator.transfer.TransferException;
import com.nordstrom.emulator.transfer.TransferHost;
import com.nordstrom.emulator.transfer.TransferOperations;
import com.nordstrom.emulator.transfer.TransferSession;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/**
 * The host side of the transfer card: the window a session opens
 * (TRANSFER-CARD.md 3). It shows the guest's volumes and directories, as
 * the adapter reports them, and imports and exports through
 * {@link TransferOperations}. All adapter traffic runs on one worker thread;
 * this class touches Swing only on the event thread.
 */
final class TransferWindow implements TransferHost {

    /** How long the Apple may stay silent during a request before the user is offered a choice. */
    static final long SILENCE_SECONDS = 10;

    private static final Preferences PREFS = Preferences.userNodeForPackage(TransferWindow.class);
    private static final String IMPORT_DIRECTORY_KEY = "transferImportDirectory";
    private static final String EXPORT_DIRECTORY_KEY = "transferExportDirectory";

    private final JFrame owner;
    private final Path initialDirectory;

    // event thread only
    private JFrame window;
    private TransferSession session;
    private TransferOperations ops;
    private ExecutorService worker;
    private JTree tree;
    private DefaultTreeModel model;
    private JTextArea log;
    private JProgressBar progress;
    private JPanel silenceBanner;
    private JLabel silenceLabel;
    private Timer silenceTimer;
    private long silenceDismissedUntil;
    private javax.swing.JRadioButton importMode;
    private javax.swing.JRadioButton exportMode;
    private JLabel status;
    private JButton actionButton;
    private JButton refreshButton;
    private JButton doneButton;
    private boolean finishing;
    private final AtomicBoolean busy = new AtomicBoolean();

    /**
     * @param owner            the emulator's main window, for placement; may be null
     * @param initialDirectory where host dialogs open first if nothing is remembered; may be null
     */
    TransferWindow(JFrame owner, Path initialDirectory) {
        this.owner = owner;
        this.initialDirectory = initialDirectory;
    }

    // ---- TransferHost: called on the emulation thread ----

    @Override
    public void sessionStarted(TransferSession session) {
        SwingUtilities.invokeLater(() -> open(session));
    }

    @Override
    public void sessionEnded(TransferSession session, EndReason reason) {
        SwingUtilities.invokeLater(() -> ended(session, reason));
    }

    // ---- window lifecycle ----

    private void open(TransferSession newSession) {
        if (window != null) {
            closeWindow();
        }
        session = newSession;
        ops = new TransferOperations(newSession);
        // Per-session state starts fresh: this object outlives each window,
        // and a previous session's Done left finishing set.
        finishing = false;
        silenceDismissedUntil = 0;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "host-transfer");
            t.setDaemon(true);
            return t;
        });
        Capabilities caps = newSession.capabilities();
        window = new JFrame("Host File Transfer -- " + (caps.osName() + " " + caps.osVersion()).trim());
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                finish();
            }
        });

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Apple");
        model = new DefaultTreeModel(root);
        tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (node.getUserObject() instanceof GuestNode g && !g.loaded) {
                    load(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // nothing to do
            }
        });

        log = new JTextArea(6, 50);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        progress = new JProgressBar();
        progress.setStringPainted(true);
        progress.setString("");

        // First the action, then the Apple side: the tree is inactive, and
        // the one action button says what it will do, only once a mode is chosen.
        importMode = new javax.swing.JRadioButton("Import files into the Apple");
        exportMode = new javax.swing.JRadioButton("Export a file to the host");
        javax.swing.ButtonGroup modes = new javax.swing.ButtonGroup();
        modes.add(importMode);
        modes.add(exportMode);
        importMode.addActionListener(e -> modeChanged());
        exportMode.addActionListener(e -> modeChanged());
        tree.addTreeSelectionListener(e -> updateActions());
        status = new JLabel(" ");

        actionButton = new JButton("Choose a mode above");
        actionButton.addActionListener(e -> {
            if (importMode.isSelected()) {
                chooseImport();
            } else if (exportMode.isSelected()) {
                chooseExport();
            }
        });
        refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshSelected());
        doneButton = new JButton("Done");
        doneButton.addActionListener(e -> finish());
        // The transfer action on the left, ending the session on the right:
        // one row that never wraps (a wrapped FlowLayout row is clipped).
        JPanel transferActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        transferActions.add(actionButton);
        transferActions.add(refreshButton);
        JPanel sessionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        sessionActions.add(doneButton);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(transferActions, BorderLayout.WEST);
        buttons.add(sessionActions, BorderLayout.EAST);

        silenceLabel = new JLabel();
        JButton keepWaiting = new JButton("Keep Waiting");
        keepWaiting.addActionListener(e -> {
            silenceDismissedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(SILENCE_SECONDS);
            silenceBanner.setVisible(false);
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            ops.cancel();
            appendLog("Cancelled -- the Apple wasn't responding. Press RESET on the Apple if it stays stuck.");
            closeWindow();
        });
        silenceBanner = new JPanel(new FlowLayout(FlowLayout.LEFT));
        silenceBanner.setBorder(BorderFactory.createEtchedBorder());
        silenceBanner.add(silenceLabel);
        silenceBanner.add(keepWaiting);
        silenceBanner.add(cancel);
        silenceBanner.setVisible(false);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(silenceBanner);
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusRow.add(status);
        south.add(statusRow);
        south.add(progress);
        south.add(buttons);

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modeRow.add(new JLabel("What would you like to do?"));
        modeRow.add(importMode);
        modeRow.add(exportMode);
        JPanel top = new JPanel(new BorderLayout());
        top.add(modeRow, BorderLayout.NORTH);
        top.add(new JScrollPane(tree), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, new JScrollPane(log));
        split.setResizeWeight(0.7);

        window.getContentPane().add(split, BorderLayout.CENTER);
        window.getContentPane().add(south, BorderLayout.SOUTH);
        window.pack(); // wide enough for every button
        window.setSize(Math.max(window.getWidth(), 640), 520);
        window.setMinimumSize(new java.awt.Dimension(window.getWidth(), 360));
        window.setLocationRelativeTo(owner);
        window.setVisible(true);

        silenceTimer = new Timer(1000, e -> checkSilence());
        silenceTimer.start();
        updateActions();
        loadVolumes();
    }

    private void modeChanged() {
        // One item either way: a destination to import into, or the file to
        // export -- export is one file per request, saved under a name chosen
        // in a save dialog.
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.clearSelection();
        updateActions();
    }

    /**
     * Enables what can act now and says what the action will apply to --
     * from the mode, the selection, and whether work is under way. Called
     * whenever any of those change.
     */
    void updateActions() {
        if (window == null) {
            return;
        }
        boolean idle = !busy.get() && !finishing && session.isOpen();
        boolean chosen = importMode.isSelected() || exportMode.isSelected();
        importMode.setEnabled(idle);
        exportMode.setEnabled(idle);
        tree.setEnabled(chosen && !finishing);
        refreshButton.setEnabled(idle && chosen && tree.getSelectionPath() != null);
        doneButton.setEnabled(!finishing || !session.isOpen());
        if (importMode.isSelected()) {
            DefaultMutableTreeNode target = selectedContainerNode();
            actionButton.setText("Choose Host Files to Import...");
            actionButton.setEnabled(idle && target != null);
            status.setText(target == null
                ? "Select the volume or folder on the Apple to import into (or a file in it)."
                : "Import into: " + String.join("/", ((GuestNode) target.getUserObject()).path));
        } else if (exportMode.isSelected()) {
            List<GuestNode> files = selectedFiles();
            actionButton.setText("Export File...");
            actionButton.setEnabled(idle && files.size() == 1);
            status.setText(files.isEmpty()
                ? "Select the file on the Apple to export."
                : "Export: " + String.join("/", files.get(0).path));
        } else {
            actionButton.setText("Choose an action above");
            actionButton.setEnabled(false);
            status.setText("Choose whether to import files into the Apple or export them to the host.");
        }
    }

    private void ended(TransferSession endedSession, EndReason reason) {
        if (endedSession != session || window == null) {
            return; // a session this window already let go of
        }
        if (reason == EndReason.COMPLETED) {
            closeWindow();
            return;
        }
        appendLog(reason == EndReason.RESET
            ? "The Apple was reset, so the session ended. Files not marked done above were not transferred."
            : "The Apple started a new session, so this one ended.");
        doneButton.setText("Close");
        finishing = true; // closing now just closes
        silenceBanner.setVisible(false);
        updateActions();
    }

    /** Done, or the window's close box: ask the adapter to END; the window closes when it does. */
    private void finish() {
        if (finishing || !session.isOpen()) {
            closeWindow();
            return;
        }
        if (busy.get()) {
            int answer = JOptionPane.showConfirmDialog(window, "A transfer is in progress. Stop it and finish?",
                "Host File Transfer", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
            ops.cancel();
        }
        finishing = true;
        updateActions();
        appendLog("Finishing -- the Apple is restoring its memory...");
        ops.end();
    }

    private void closeWindow() {
        if (silenceTimer != null) {
            silenceTimer.stop();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
        if (window != null) {
            window.dispose();
            window = null;
            if (owner != null) { // keyboard focus back to the Apple: typing goes
                owner.toFront(); // to the focused window, and nothing else
                owner.requestFocus(); // would return it there
            }
        }
    }

    /** Disposes the window, e.g. when the machine it belongs to is replaced. */
    void dispose() {
        closeWindow();
    }

    private void checkSilence() {
        boolean waiting = (busy.get() || finishing) && session.isOpen();
        long silentNanos = session.nanosSinceAdapterActivity();
        if (waiting && silentNanos > TimeUnit.SECONDS.toNanos(SILENCE_SECONDS) && System.nanoTime() > silenceDismissedUntil) {
            silenceLabel.setText("The Apple hasn't responded for " + TimeUnit.NANOSECONDS.toSeconds(silentNanos) + " seconds.");
            silenceBanner.setVisible(true);
        } else if (!waiting || silentNanos < TimeUnit.SECONDS.toNanos(1)) {
            silenceBanner.setVisible(false);
        }
    }

    // ---- the guest tree ----

    /** A tree node's guest object: a volume, directory or file. */
    static final class GuestNode {
        final List<String> path;
        final GuestEntry entry; // null for a volume
        boolean loaded;

        GuestNode(List<String> path, GuestEntry entry) {
            this.path = path;
            this.entry = entry;
        }

        boolean isContainer() {
            return entry == null || entry.directory();
        }

        @Override
        public String toString() {
            String name = path.get(path.size() - 1);
            if (isContainer()) {
                return name;
            }
            String type = entry.auxKnown()
                ? String.format("%s $%04X", entry.type().tag(), entry.type().aux())
                : entry.type().tag(); // known once the file is read
            String size = String.format(entry.sizeExact() ? "%,d bytes" : "about %,d bytes", entry.size());
            return String.format("%s   (%s, %s)", name, type, size);
        }
    }

    private void loadVolumes() {
        runOnWorker("Reading the Apple's volumes...", () -> {
            List<String> volumes = ops.volumes();
            SwingUtilities.invokeLater(() -> {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
                root.removeAllChildren();
                for (String v : volumes) {
                    root.add(containerNode(new GuestNode(List.of(v), null)));
                }
                model.reload();
            });
        });
    }

    private static DefaultMutableTreeNode containerNode(GuestNode g) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(g);
        node.add(new DefaultMutableTreeNode("(loading...)"));
        return node;
    }

    private void load(DefaultMutableTreeNode node) {
        GuestNode g = (GuestNode) node.getUserObject();
        g.loaded = true;
        runOnWorker("Reading " + String.join("/", g.path) + "...", () -> {
            List<GuestEntry> entries = ops.list(g.path);
            SwingUtilities.invokeLater(() -> {
                node.removeAllChildren();
                for (GuestEntry e : entries) {
                    List<String> childPath = new ArrayList<>(g.path);
                    childPath.add(e.name());
                    GuestNode child = new GuestNode(childPath, e);
                    node.add(e.directory() ? containerNode(child) : new DefaultMutableTreeNode(child));
                }
                model.nodeStructureChanged(node);
            });
        });
    }

    private void refreshSelected() {
        DefaultMutableTreeNode node = selectedContainerNode();
        if (node == null) {
            loadVolumes();
        } else {
            load(node);
            tree.expandPath(new TreePath(node.getPath()));
        }
    }

    /** The selected volume or directory -- or the directory holding the selected file. */
    private DefaultMutableTreeNode selectedContainerNode() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof GuestNode g && !g.isContainer()) {
            node = (DefaultMutableTreeNode) node.getParent();
        }
        return node.getUserObject() instanceof GuestNode ? node : null;
    }

    private List<GuestNode> selectedFiles() {
        List<GuestNode> files = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath p : paths) {
                if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof GuestNode g
                        && !g.isContainer()) {
                    files.add(g);
                }
            }
        }
        return files;
    }

    // ---- import ----

    /**
     * Choose Host Files to Import...: first check that files can be written
     * to the destination -- a disk there, readable, unprotected -- so a
     * problem is explained before any files are chosen, not reported per
     * file after.
     */
    private void chooseImport() {
        DefaultMutableTreeNode target = selectedContainerNode();
        if (target == null) {
            return; // the button is enabled only with a destination
        }
        GuestNode destination = (GuestNode) target.getUserObject();
        String where = String.join("/", destination.path);
        runOnWorker("Checking " + where + "...", () -> {
            com.nordstrom.emulator.transfer.ResultCode check = ops.checkWritable(destination.path);
            SwingUtilities.invokeLater(() -> {
                if (check == com.nordstrom.emulator.transfer.ResultCode.OK) {
                    chooseImportFiles(target);
                } else if (check != null) { // null: cancelled
                    explainUnwritable(where, check);
                }
            });
        });
    }

    /** Why files can't be imported into a destination, in the log and a message. */
    private void explainUnwritable(String where, com.nordstrom.emulator.transfer.ResultCode why) {
        String problem;
        String remedy;
        switch (why) {
            case WRITE_PROTECTED -> {
                problem = "The disk holding " + where + " is write-protected.";
                remedy = "Remove the protection (Disk menu) and try again.";
            }
            case NOT_FOUND, IO_ERROR -> {
                problem = where + " can't be read -- is its disk in the drive?";
                remedy = "Insert it (Disk menu) and try again.";
            }
            default -> {
                problem = "Files can't be written to " + where + " (" + why + ").";
                remedy = "";
            }
        }
        appendLog("Import into " + where + ": " + problem);
        JOptionPane.showMessageDialog(window, remedy.isEmpty() ? problem : problem + "\n" + remedy,
            "Import", JOptionPane.WARNING_MESSAGE);
    }

    /** The host files to import into a destination already checked, then their names on the Apple. */
    private void chooseImportFiles(DefaultMutableTreeNode target) {
        JFileChooser chooser = new JFileChooser(startDirectory(IMPORT_DIRECTORY_KEY));
        chooser.setDialogTitle("Import from Host");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] chosen = chooser.getSelectedFiles();
        if (chosen.length == 0) {
            return;
        }
        PREFS.put(IMPORT_DIRECTORY_KEY, chosen[0].getParentFile().getAbsolutePath());

        Capabilities caps = session.capabilities();
        DefaultTableModel names = new DefaultTableModel(new Object[] {"Host file", "Name on the Apple"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        for (File f : chosen) {
            names.addRow(new Object[] {f.getName(), GuestNames.suggest(HostNames.decode(f.getName()).guestName(), caps)});
        }
        JTable table = new JTable(names);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new java.awt.Dimension(460, Math.min(300, 40 + 20 * chosen.length)));
        int answer = JOptionPane.showConfirmDialog(window, scroll, "Names on the Apple",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        List<Path> files = new ArrayList<>();
        List<String> guestNames = new ArrayList<>();
        for (int i = 0; i < chosen.length; i++) {
            files.add(chosen[i].toPath());
            guestNames.add(names.getValueAt(i, 1).toString().trim());
        }
        importFiles(files, target, guestNames);
    }

    /** Imports host files into a guest container -- the part after the dialogs. Package-visible for tests. */
    void importFiles(List<Path> files, DefaultMutableTreeNode target, List<String> guestNames) {
        GuestNode container = (GuestNode) target.getUserObject();
        runOnWorker("Importing...", () -> {
            for (int i = 0; i < files.size() && !ops.isCancelled(); i++) {
                setProgress(i, files.size(), "Importing " + files.get(i).getFileName());
                TransferOperations.Result r = ops.importFile(files.get(i), container.path, guestNames.get(i), dialogs());
                logResult(files.get(i).getFileName() + " -> " + r.name(), r);
            }
            SwingUtilities.invokeLater(() -> {
                load(target);
                tree.expandPath(new TreePath(target.getPath()));
            });
        });
    }

    // ---- export ----

    /**
     * Export File...: read the selected file first -- its host name may depend
     * on what reading learns -- then ask where to save it, with that name
     * suggested.
     */
    private void chooseExport() {
        List<GuestNode> files = selectedFiles();
        if (files.size() != 1) {
            return; // the button is enabled only for one file
        }
        GuestNode file = files.get(0);
        runOnWorker("Reading " + file.entry.name() + "...", () -> {
            TransferOperations.ExportRead read = ops.readForExport(file.path, file.entry);
            SwingUtilities.invokeLater(() -> saveExported(file, read));
        });
    }

    /** The save dialog for a file read for export: any folder, any name, the convention's name suggested. */
    private void saveExported(GuestNode file, TransferOperations.ExportRead read) {
        String source = String.join("/", file.path);
        if (!read.succeeded()) {
            logResult(source, read.failure());
            return;
        }
        JFileChooser chooser = new JFileChooser(startDirectory(EXPORT_DIRECTORY_KEY));
        chooser.setDialogTitle("Export " + file.entry.name() + " to the Host");
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), read.hostName()));
        while (chooser.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            if (target.isDirectory()) {
                chooser.setCurrentDirectory(target); // a folder: look inside it
                chooser.setSelectedFile(new File(target, read.hostName()));
                continue;
            }
            if (target.exists() && !ask(target.getName() + " already exists. Replace it?")) {
                continue; // back to the dialog
            }
            PREFS.put(EXPORT_DIRECTORY_KEY, target.getParentFile().getAbsolutePath());
            TransferOperations.Result r = ops.saveExport(read, target.toPath());
            logResult(source + " -> " + target, r);
            return;
        }
        appendLog(source + ": export cancelled.");
    }

    // ---- helpers ----

    private File startDirectory(String key) {
        String last = PREFS.get(key, null);
        if (last != null && new File(last).isDirectory()) {
            return new File(last);
        }
        return initialDirectory != null ? initialDirectory.toFile() : null;
    }

    /** Conflict questions, asked on the event thread while the worker waits. */
    private TransferOperations.Conflicts dialogs() {
        return new TransferOperations.Conflicts() {
            @Override
            public boolean replaceGuestFile(String guestName) {
                return ask(guestName + " already exists on the Apple. Replace it?");
            }

            @Override
            public boolean overwriteHostFile(Path hostFile) {
                return ask(hostFile.getFileName() + " already exists in " + hostFile.getParent() + ". Overwrite it?");
            }
        };
    }

    /** A yes/no question, asked on the event thread -- from it or from the worker. */
    private boolean ask(String question) {
        if (SwingUtilities.isEventDispatchThread()) {
            return window != null && JOptionPane.showConfirmDialog(window, question, "Host File Transfer",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
        }
        boolean[] yes = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> yes[0] = window != null && JOptionPane.showConfirmDialog(window,
                question, "Host File Transfer", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            return false;
        }
        return yes[0];
    }

    private void runOnWorker(String status, WorkerTask task) {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        busy.set(true);
        updateActions();
        worker.execute(() -> {
            setProgress(-1, 0, status);
            try {
                task.run();
            } catch (TransferException e) {
                appendLaterLog("Couldn't read from the Apple: " + e.getMessage());
            } catch (RuntimeException e) {
                appendLaterLog("Unexpected error: " + e);
            } finally {
                busy.set(false);
                setProgress(0, 0, "");
                SwingUtilities.invokeLater(this::updateActions);
            }
        });
    }

    private interface WorkerTask {
        void run() throws TransferException;
    }

    private void setProgress(int done, int total, String text) {
        SwingUtilities.invokeLater(() -> {
            if (progress == null) {
                return;
            }
            progress.setIndeterminate(done < 0);
            progress.setMaximum(Math.max(total, 1));
            progress.setValue(Math.max(done, 0));
            progress.setString(text);
        });
    }

    private void logResult(String what, TransferOperations.Result r) {
        String outcome = switch (r.outcome()) {
            case DONE -> "done";
            case SKIPPED -> "skipped";
            case FAILED -> "FAILED";
            case CANCELLED -> "cancelled";
        };
        appendLaterLog(what + ": " + outcome + (r.detail().isEmpty() ? "" : " -- " + r.detail()));
    }

    private void appendLaterLog(String line) {
        SwingUtilities.invokeLater(() -> appendLog(line));
    }

    private void appendLog(String line) {
        if (log != null) {
            log.append(line + "\n");
        }
    }

    // ---- package-visible for tests ----

    JFrame windowForTests() {
        return window;
    }

    JTree treeForTests() {
        return tree;
    }

    String logForTests() {
        return log == null ? "" : log.getText();
    }

    boolean isBusyForTests() {
        return busy.get();
    }
}
