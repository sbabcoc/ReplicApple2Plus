package com.nordstrom.emulator;

import com.nordstrom.emulator.system.CardCatalog;
import com.nordstrom.emulator.system.CardParameter;
import com.nordstrom.emulator.system.SlotCardLoader;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The Settings dialog's Machine tab: the slot configuration a Reboot will
 * use, slot by slot (see {@link SlotEditorModel}).
 * <p>
 * At the top, which file that is and where the choice comes from, with
 * Open... to use another, Save As... to make a variant, and Use Emulator
 * Folder's slots.ini to go back to the default; a banner says when a Reboot
 * is needed to use what's saved. Below, the eight slots, each marked if it
 * has a problem; choosing one shows its card, the card's settings, and its
 * problems. Problems no slot can show -- lines that aren't INI -- are listed
 * above the slots, with Edit as Text... to open the file in the system's
 * editor and Reload to read it back.
 * <p>
 * Edits are kept until Save, which is enabled only once nothing is wrong.
 * A file chosen with Open... is used only once it has no problems: one
 * that has some opens for repair, and is used when it's saved.
 */
final class SlotEditorPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Color PROBLEM = new Color(0xC6, 0x28, 0x28);

    private final transient Apple2Plus.StartupFiles startup;
    private final transient SlotEditorModel model;

    private final JTextField fileField = new JTextField(36);
    private final JLabel sourceNote = new JLabel();
    private final JLabel banner = new JLabel();
    private final JLabel fileProblems = new JLabel();
    private final DefaultListModel<Integer> slots = new DefaultListModel<>();
    private final JList<Integer> slotList = new JList<>(slots);
    private final JPanel detail = new JPanel(new GridBagLayout());
    private final JLabel slotProblems = new JLabel();
    private final JButton open = new JButton("Open...");
    private final JButton saveAs = new JButton("Save As...");
    private final JButton useFolder = new JButton("Use Emulator Folder's slots.ini");
    private final JButton editAsText = new JButton("Edit as Text...");
    private final JButton reload = new JButton("Reload");
    private final JButton save = new JButton("Save");

    /** True while the detail pane is being rebuilt, when its controls' listeners mustn't edit the model. */
    private boolean building;

    /**
     * @param startup how the emulator was started: the class loader cards come from,
     *                and whether {@code --config} fixes the configuration for this run
     * @throws IOException if the configuration exists but can't be read
     */
    SlotEditorPanel(Apple2Plus.StartupFiles startup) throws IOException {
        super(new BorderLayout(0, 8));
        this.startup = startup;
        this.model = new SlotEditorModel(CardCatalog.of(startup.classLoader()), initialFile());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        fileField.setEditable(false);
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.add(new JLabel("Configuration:"), BorderLayout.WEST);
        fileRow.add(fileField, BorderLayout.CENTER);
        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fileButtons.add(open);
        fileButtons.add(saveAs);
        fileButtons.add(useFolder);
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        for (JComponent row : List.of(fileRow, sourceNote, fileButtons, banner, fileProblems)) {
            row.setAlignmentX(LEFT_ALIGNMENT);
            header.add(row);
            header.add(Box.createVerticalStrut(4));
        }
        small(sourceNote);
        fileProblems.setForeground(PROBLEM);
        add(header, BorderLayout.NORTH);

        for (int slot = 0; slot < SlotEditorModel.SLOTS; slot++) {
            slots.addElement(slot);
        }
        slotList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        slotList.setCellRenderer(new SlotRenderer());
        slotList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                rebuildDetail();
            }
        });
        JScrollPane listScroll = new JScrollPane(slotList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setPreferredSize(new java.awt.Dimension(310, 230));
        detail.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        JPanel detailHolder = new JPanel(new BorderLayout());
        detailHolder.add(detail, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.add(listScroll, BorderLayout.WEST);
        // A fixed size, so the dialog doesn't change size from one slot to the next.
        JScrollPane detailScroll = new JScrollPane(detailHolder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.setPreferredSize(new java.awt.Dimension(560, 230));
        body.add(detailScroll, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(editAsText);
        left.add(reload);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(save);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        open.addActionListener(event -> openOther());
        saveAs.addActionListener(event -> saveAs());
        useFolder.addActionListener(event -> useEmulatorFolders());
        editAsText.addActionListener(event -> editAsText());
        reload.addActionListener(event -> {
            if (discardEdits("Reload")) {
                load(model.file());
            }
        });
        save.addActionListener(event -> save());

        slotList.setSelectedIndex(firstInterestingSlot());
        refresh();
    }

    /** The configuration a Reboot would use, else the emulator folder's slots.ini to create, else none yet. */
    private static Path initialFile() {
        Path reboot = Apple2Plus.rebootConfiguration();
        return reboot != null ? reboot : EmulatorFolder.slotConfiguration();
    }

    /** The first slot with a problem, so it's in view; else the first with a card; else slot 0. */
    private int firstInterestingSlot() {
        int firstCard = -1;
        for (int slot = 0; slot < SlotEditorModel.SLOTS; slot++) {
            SlotEditorModel.SlotState state = model.slot(slot);
            if (!state.problems().isEmpty()) {
                return slot;
            }
            if (firstCard < 0 && state.present()) {
                firstCard = slot;
            }
        }
        return Math.max(firstCard, 0);
    }

    /**
     * Asks about unsaved edits before the dialog closes.
     *
     * @return true if it may close: there were no edits, or they were saved or discarded
     */
    boolean confirmClose() {
        if (!model.dirty()) {
            return true;
        }
        Object[] options = {"Save", "Discard", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "The slot configuration has unsaved changes.",
            "Settings", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            return save();
        }
        return choice == 1;
    }

    /** Re-reads the emulator folder's choice, as after the Emulator tab changes it -- unless there are edits. */
    void configurationChoiceChanged() {
        if (!model.dirty()) {
            load(initialFile());
        } else {
            refresh();
        }
    }

    // ----- actions

    private void openOther() {
        if (!discardEdits("Open another configuration")) {
            return;
        }
        JFileChooser chooser = new JFileChooser(model.file() != null ? model.file().getParent().toFile() : null);
        chooser.setDialogTitle("Open Slot Configuration");
        chooser.setFileFilter(new FileNameExtensionFilter("Slot configurations (*.ini)", "ini"));
        if (chooser.showDialog(this, "Open") != JFileChooser.APPROVE_OPTION) {
            return;
        }
        load(chooser.getSelectedFile().toPath());
        if (!model.hasProblems() && loadsCleanly(model.file())) {
            use(model.file());
            refresh();
        }
    }

    private void saveAs() {
        JFileChooser chooser = new JFileChooser(model.file() != null ? model.file().getParent().toFile() : null);
        chooser.setDialogTitle("Save Slot Configuration As");
        chooser.setFileFilter(new FileNameExtensionFilter("Slot configurations (*.ini)", "ini"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        if (!chosen.getName().contains(".")) {
            chosen = new File(chosen.getParentFile(), chosen.getName() + ".ini");
        }
        if (chosen.exists() && JOptionPane.showConfirmDialog(this, chosen.getName() + " already exists. Replace it?",
                "Save As", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            model.saveAs(chosen.toPath(), startup.classLoader());
        } catch (IOException | RuntimeException | LinkageError e) {
            showError("save", e);
            return;
        }
        use(model.file());
        refresh();
    }

    private void useEmulatorFolders() {
        if (!discardEdits("Use the emulator folder's slots.ini")) {
            return;
        }
        RememberedFiles.forgetSlotConfiguration();
        load(initialFile());
    }

    private void editAsText() {
        Path file = model.file();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.EDIT)) {
                Desktop.getDesktop().edit(file.toFile());
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.toFile());
                return;
            }
        } catch (IOException | RuntimeException e) {
            // fall through to showing where it is
        }
        JOptionPane.showMessageDialog(this, "Open this file in a text editor, then click Reload:\n" + file,
            "Edit as Text", JOptionPane.INFORMATION_MESSAGE);
    }

    /** @return true if it was saved */
    private boolean save() {
        if (model.file() == null) {
            saveAs();
            return !model.dirty();
        }
        try {
            model.save(startup.classLoader());
        } catch (SlotEditorModel.ChangedOnDiskException e) {
            Object[] options = {"Reload", "Cancel"};
            if (JOptionPane.showOptionDialog(this, model.file().getFileName() + " has changed since it was opened.\n"
                    + "Reload it, discarding your changes here?", "Save", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0) {
                load(model.file());
            }
            return false;
        } catch (IOException | RuntimeException | LinkageError e) {
            showError("save", e);
            return false;
        }
        use(model.file());
        refresh();
        return true;
    }

    /**
     * Makes a saved, clean configuration the one Reboot uses: remembered for
     * the emulator folder, unless it's the folder's own slots.ini. With
     * {@code --config}, this run's choice is fixed, so nothing is remembered.
     */
    private void use(Path file) {
        if (startup.configGiven()) {
            return;
        }
        if (file.equals(normalized(EmulatorFolder.slotConfiguration()))) {
            RememberedFiles.forgetSlotConfiguration();
        } else {
            RememberedFiles.rememberSlotConfiguration(file);
        }
    }

    private boolean loadsCleanly(Path file) {
        try {
            SlotCardLoader.load(file, startup.classLoader());
            return true;
        } catch (IOException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private void load(Path file) {
        Integer selected = slotList.getSelectedValue();
        try {
            model.load(file);
        } catch (IOException e) {
            showError("read", e);
        }
        slotList.setSelectedIndex(selected != null ? selected : firstInterestingSlot());
        refresh();
        rebuildDetail();
    }

    /** @return true if there are no edits, or the user agrees to discard them */
    private boolean discardEdits(String action) {
        return !model.dirty() || JOptionPane.showConfirmDialog(this,
            "Discard the unsaved changes to the slot configuration?", action, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /**
     * Reports a failure. Loading for real can fail with an error from a
     * card's own class -- a ROM it couldn't load, a plug-in that doesn't
     * link -- whose message is on its cause.
     */
    private void showError(String verb, Throwable e) {
        Throwable reported = e;
        while (reported.getMessage() == null && reported.getCause() != null) {
            reported = reported.getCause();
        }
        String message = reported.getMessage() != null ? reported.getMessage() : reported.toString();
        JOptionPane.showMessageDialog(this, "Couldn't " + verb + " the slot configuration:\n" + message,
            "Slot Configuration", JOptionPane.ERROR_MESSAGE);
    }

    // ----- display

    /** Updates everything but the detail pane's controls, which hold edits in progress. */
    private void refresh() {
        Path file = model.file();
        fileField.setText(file != null ? file.toString() : "(not saved yet)");
        sourceNote.setText(sourceText());

        boolean fixed = startup.configGiven();
        open.setEnabled(!fixed);
        saveAs.setEnabled(!fixed);
        useFolder.setEnabled(!fixed && EmulatorFolder.slotConfiguration() != null
            && !Objects.equals(file, normalized(EmulatorFolder.slotConfiguration())));
        editAsText.setEnabled(file != null && Files.exists(file) && !model.dirty());
        editAsText.setToolTipText(model.dirty() ? "Save or reload first" : null);
        reload.setEnabled(file != null && Files.exists(file));
        boolean problems = model.hasProblems();
        save.setEnabled((model.dirty() || file == null || !Files.exists(file)) && !problems);
        save.setToolTipText(problems ? "Fix the problems first" : null);

        List<String> lines = model.fileProblems();
        fileProblems.setVisible(!lines.isEmpty());
        fileProblems.setText(html("Edit as Text... to fix these, then Reload:", lines));

        banner.setText(bannerText());
        banner.setVisible(!banner.getText().isEmpty());
        slotList.repaint();
        updateSlotProblems();
    }

    private String sourceText() {
        Path file = model.file();
        if (startup.configGiven()) {
            return "Given with --config, so this run's Reboot always uses it.";
        }
        if (file == null) {
            return "There's no emulator folder; Save As... to keep this configuration.";
        }
        if (file.equals(Apple2Plus.rebootConfiguration())) {
            return file.equals(normalized(EmulatorFolder.slotConfiguration()))
                ? "The emulator folder's slots.ini." : "Chosen in place of the emulator folder's slots.ini.";
        }
        if (file.equals(normalized(EmulatorFolder.slotConfiguration()))) {
            return "The emulator folder's slots.ini, not created yet: Save creates it.";
        }
        return "Not in use yet: it's used once it's saved with no problems.";
    }

    private String bannerText() {
        List<String> notes = new ArrayList<>();
        if (model.dirty()) {
            notes.add("Unsaved changes.");
        }
        Path file = model.file();
        if (file != null && file.equals(Apple2Plus.rebootConfiguration()) && !matchesRunningMachine(file)) {
            notes.add("Reboot to use the saved configuration.");
        }
        return notes.isEmpty() ? "" : "<html><b>" + String.join(" ", notes) + "</b></html>";
    }

    /** @return true if the running machine was built from this file, as it's saved now */
    private static boolean matchesRunningMachine(Path file) {
        Apple2Plus.RunningConfiguration running = Apple2Plus.runningConfiguration();
        if (!file.equals(running.path())) {
            return false;
        }
        try {
            return Files.readAllLines(file).equals(running.lines());
        } catch (IOException e) {
            return false;
        }
    }

    private void updateSlotProblems() {
        Integer slot = slotList.getSelectedValue();
        List<String> problems = slot != null ? model.slot(slot).problems() : List.of();
        slotProblems.setText(html(null, problems));
        slotProblems.setVisible(!problems.isEmpty());
    }

    /** Rebuilds the detail pane for the selected slot. */
    private void rebuildDetail() {
        building = true;
        try {
            detail.removeAll();
            Integer selected = slotList.getSelectedValue();
            if (selected != null) {
                buildDetail(selected);
            }
            detail.revalidate();
            detail.repaint();
        } finally {
            building = false;
        }
        refresh();
    }

    private void buildDetail(int slot) {
        SlotEditorModel.SlotState state = model.slot(slot);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 0, 8, 6);

        JLabel title = new JLabel("Slot " + slot);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        c.gridx = 0;
        c.gridwidth = 3;
        detail.add(title, c);
        c.gridwidth = 1;
        c.gridx = 3;
        c.weightx = 1; // takes the spare width, keeping the rows against the left
        detail.add(Box.createHorizontalGlue(), c);
        c.weightx = 0;

        c.gridy++;
        c.gridx = 0;
        detail.add(new JLabel("Card:"), c);
        JComboBox<Choice> cards = new JComboBox<>(choices(state).toArray(new Choice[0]));
        cards.setSelectedItem(currentChoice(state, cards));
        cards.addActionListener(event -> {
            Choice choice = (Choice) cards.getSelectedItem();
            if (building || choice == null || choice.placeholder()) {
                return;
            }
            model.setCard(slot, choice.card());
            rebuildDetail();
        });
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        detail.add(cards, c);
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;

        if (state.card() != null) {
            for (CardParameter parameter : state.card().parameters()) {
                c.gridy++;
                addParameter(slot, parameter, state.entries().getOrDefault(parameter.name(), ""), c);
            }
            for (String key : state.undeclared()) {
                c.gridy++;
                c.gridx = 0;
                detail.add(new JLabel(key + ":"), c);
                c.gridx = 1;
                JLabel value = new JLabel(state.entries().get(key));
                value.setForeground(PROBLEM);
                detail.add(value, c);
                JButton remove = new JButton("Remove");
                remove.addActionListener(event -> {
                    model.setValue(slot, key, "");
                    rebuildDetail();
                });
                c.gridx = 2;
                detail.add(remove, c);
            }
        } else if (state.present() && state.type() != null) {
            c.gridy++;
            c.gridx = 1;
            c.gridwidth = 2;
            JLabel note = new JLabel("<html><div style='width:300px'>Its settings can't be shown until the card "
                + "is found. Choose another card, or Empty, to replace it.</div></html>");
            small(note);
            detail.add(note, c);
            c.gridwidth = 1;
        }

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        slotProblems.setForeground(PROBLEM);
        detail.add(slotProblems, c);
    }

    /** One setting's row: its label, a field, and for a path a Browse... button, with a hint below. */
    private void addParameter(int slot, CardParameter parameter, String value, GridBagConstraints c) {
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        detail.add(new JLabel(parameter.label() + ":"), c);

        JTextField field = new JTextField(value, 18);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                if (!building) {
                    model.setValue(slot, parameter.name(), field.getText());
                    refresh();
                }
            }
        });
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        detail.add(field, c);
        c.fill = GridBagConstraints.NONE;

        if (parameter.kind() != CardParameter.Kind.INTEGER) {
            JButton browse = new JButton("Browse...");
            browse.addActionListener(event -> browse(parameter, field));
            c.gridx = 2;
            detail.add(browse, c);
        }

        String hint = switch (parameter.kind()) {
            case FILE, FOLDER -> "Relative to the configuration's folder, or absolute. Blank for none.";
            case INTEGER -> parameter.min() + " to " + parameter.max() + ". Blank for the default, "
                + parameter.defaultValue() + ".";
        };
        JLabel hintLabel = new JLabel("<html><div style='width:260px'>" + escape(hint) + "</div></html>");
        small(hintLabel);
        c.gridy++;
        c.gridx = 1;
        c.gridwidth = 2;
        Insets row = c.insets;
        c.insets = new Insets(-6, 0, 8, 6);
        detail.add(hintLabel, c);
        c.insets = row;
        c.gridwidth = 1;
    }

    private void browse(CardParameter parameter, JTextField field) {
        String current = field.getText().trim();
        Path start = current.isEmpty() ? (model.file() != null ? model.file().getParent() : null) : model.resolve(current);
        JFileChooser chooser = new JFileChooser(start != null ? start.toFile() : null);
        chooser.setDialogTitle(parameter.label());
        if (parameter.kind() == CardParameter.Kind.FOLDER) {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        } else {
            if (start != null && Files.isRegularFile(start)) {
                chooser.setSelectedFile(start.toFile());
            }
            if (!parameter.extensions().isEmpty()) {
                chooser.setFileFilter(new FileNameExtensionFilter(parameter.label() + " (*."
                    + String.join(", *.", parameter.extensions()) + ")", parameter.extensions().toArray(new String[0])));
            }
        }
        if (chooser.showDialog(this, "Choose") == JFileChooser.APPROVE_OPTION) {
            field.setText(model.pathValue(chooser.getSelectedFile().toPath()));
        }
    }

    /**
     * A card the slot's drop-down offers. A placeholder stands for what the
     * file has when it isn't a card that can be offered -- an unknown type --
     * and choosing it changes nothing.
     */
    private record Choice(CardCatalog.CardDescription card, String label, boolean placeholder) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Choice EMPTY = new Choice(null, "Empty", false);

    /** Empty, every card the slot can take, and whatever the file has that isn't one of those. */
    private List<Choice> choices(SlotEditorModel.SlotState state) {
        List<Choice> choices = new ArrayList<>();
        choices.add(EMPTY);
        model.catalog().cards().stream()
            .filter(card -> card.supportedSlots().contains(state.slot()))
            .sorted(Comparator.comparing(CardCatalog.CardDescription::displayName))
            .forEach(card -> choices.add(new Choice(card, card.displayName(), false)));
        if (state.card() != null && !state.card().supportedSlots().contains(state.slot())) {
            choices.add(new Choice(state.card(), state.card().displayName() + " (not allowed here)", true));
        } else if (state.present() && state.card() == null) {
            choices.add(new Choice(null, state.type() != null ? state.type() + " (not available)" : "(no type)", true));
        }
        return choices;
    }

    private static Choice currentChoice(SlotEditorModel.SlotState state, JComboBox<Choice> cards) {
        for (int i = 0; i < cards.getItemCount(); i++) {
            Choice choice = cards.getItemAt(i);
            boolean match = state.empty() ? choice == EMPTY
                : choice.placeholder() || (state.card() != null && choice.card() == state.card());
            if (match) {
                return choice;
            }
        }
        return EMPTY;
    }

    /** Shows each slot with its card, marked if it has a problem. */
    private final class SlotRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            SlotEditorModel.SlotState state = model.slot((Integer) value);
            String card = state.empty() ? "Empty"
                : state.card() != null ? state.card().displayName()
                : state.type() != null ? state.type() : "(no type)";
            String text = "Slot " + state.slot() + ":  " + card;
            super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            if (!state.problems().isEmpty()) {
                setText(text + "  \u2014 " + (state.problems().size() == 1 ? "problem" : state.problems().size() + " problems"));
                if (!isSelected) {
                    setForeground(PROBLEM);
                }
            } else if (state.empty() && !isSelected) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return this;
        }
    }

    private static String html(String heading, List<String> lines) {
        StringBuilder html = new StringBuilder("<html><div style='width:380px'>");
        if (heading != null) {
            html.append(heading).append("<br>");
        }
        for (String line : lines) {
            html.append("&bull; ").append(escape(line)).append("<br>");
        }
        return html.append("</div></html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void small(JLabel label) {
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1));
    }

    private static Path normalized(Path path) {
        return path != null ? path.toAbsolutePath().normalize() : null;
    }
}
