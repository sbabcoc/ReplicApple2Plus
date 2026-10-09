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
    private final List<JButton> actions = new ArrayList<>();
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
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton importButton = new JButton("Import from Host...");
        importButton.addActionListener(e -> chooseImport());
        JButton exportButton = new JButton("Export to Host...");
        exportButton.addActionListener(e -> chooseExport());
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshSelected());
        doneButton = new JButton("Done");
        doneButton.addActionListener(e -> finish());
        actions.add(importButton);
        actions.add(exportButton);
        actions.add(refreshButton);
        buttons.add(importButton);
        buttons.add(exportButton);
        buttons.add(refreshButton);
        buttons.add(doneButton);

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
        south.add(progress);
        south.add(buttons);

        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(" Select a folder on the Apple to import into, or files to export."), BorderLayout.NORTH);
        top.add(new JScrollPane(tree), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, new JScrollPane(log));
        split.setResizeWeight(0.7);

        window.getContentPane().add(split, BorderLayout.CENTER);
        window.getContentPane().add(south, BorderLayout.SOUTH);
        window.setSize(560, 520);
        window.setLocationRelativeTo(owner);
        window.setVisible(true);

        silenceTimer = new Timer(1000, e -> checkSilence());
        silenceTimer.start();
        loadVolumes();
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
        actions.forEach(b -> b.setEnabled(false));
        doneButton.setText("Close");
        finishing = true; // closing now just closes
        silenceBanner.setVisible(false);
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
        actions.forEach(b -> b.setEnabled(false));
        doneButton.setEnabled(false);
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
            return String.format("%s   (%s $%04X, %,d bytes)", name, entry.type().tag(), entry.type().aux(), entry.size());
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

    private void chooseImport() {
        DefaultMutableTreeNode target = selectedContainerNode();
        if (target == null) {
            JOptionPane.showMessageDialog(window, "Select the volume or folder on the Apple to import into.");
            return;
        }
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

    private void chooseExport() {
        List<GuestNode> files = selectedFiles();
        if (files.isEmpty()) {
            JOptionPane.showMessageDialog(window, "Select the files on the Apple to export.");
            return;
        }
        JFileChooser chooser = new JFileChooser(startDirectory(EXPORT_DIRECTORY_KEY));
        chooser.setDialogTitle("Export to Host -- choose a folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File folder = chooser.getSelectedFile();
        PREFS.put(EXPORT_DIRECTORY_KEY, folder.getAbsolutePath());
        exportFiles(files, folder.toPath());
    }

    /** Exports guest files to a host folder -- the part after the dialogs. Package-visible for tests. */
    void exportFiles(List<GuestNode> files, Path folder) {
        runOnWorker("Exporting...", () -> {
            for (int i = 0; i < files.size() && !ops.isCancelled(); i++) {
                GuestNode g = files.get(i);
                setProgress(i, files.size(), "Exporting " + g.entry.name());
                TransferOperations.Result r = ops.exportFile(g.path, g.entry, folder, dialogs());
                logResult(String.join("/", g.path) + " -> " + r.name(), r);
            }
        });
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

    private boolean ask(String question) {
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
        worker.execute(() -> {
            busy.set(true);
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
