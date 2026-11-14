package com.nordstrom.emulator;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JToolBar;
import java.awt.Component;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Toolbar drop-down choosing the kind of monitor one of the emulator's
 * screens is shown on. Each screen has its own, since a real II+ owner could
 * have a color monitor on the Apple and a monochrome one on the VideoTerm.
 * Like {@link DisplayModeSelector}, it isn't focusable, so the Apple's
 * keyboard keeps the focus, and the choice is remembered with the JDK's user
 * preferences for every machine built later.
 */
final class MonitorSelector {

    /** Which screen a selector controls. */
    enum Monitor {
        /** The Apple's own video, and the VideoTerm's when it's switched into the main window. */
        MAIN("Monitor:", "mainMonitor", MonitorType.COLOR,
            MonitorType.COLOR, MonitorType.WHITE, MonitorType.GREEN, MonitorType.AMBER),
        /**
         * The Dual Monitor window. No color choice: the VideoTerm's video has
         * no color burst, so a color monitor would show it as White does.
         */
        EIGHTY_COLUMN("80-Column Monitor:", "eightyColumnMonitor", MonitorType.GREEN,
            MonitorType.WHITE, MonitorType.GREEN, MonitorType.AMBER);

        private final String caption;
        private final String key;
        private final MonitorType defaultType;
        private final MonitorType[] choices;

        Monitor(String caption, String key, MonitorType defaultType, MonitorType... choices) {
            this.caption = caption;
            this.key = key;
            this.defaultType = defaultType;
            this.choices = choices;
        }
    }

    private static final Preferences PREFS = Preferences.userNodeForPackage(MonitorSelector.class);

    private final Monitor monitor;
    private final Consumer<MonitorType> onChange;
    private final Consumer<MonitorType> remember;
    private final JComboBox<MonitorType> comboBox;
    private MonitorType current;

    /**
     * @param monitor  the screen this controls
     * @param onChange runs on the event thread with the new type after the choice changes
     */
    MonitorSelector(Monitor monitor, Consumer<MonitorType> onChange) {
        this(monitor, remembered(monitor), onChange, type -> PREFS.put(monitor.key, type.name()));
    }

    /**
     * Package-visible for tests, which mustn't change the user's remembered choice.
     *
     * @param monitor  the screen this controls
     * @param initial  the type selected at first
     * @param onChange runs with the new type after the choice changes
     * @param remember records the type chosen
     */
    MonitorSelector(Monitor monitor, MonitorType initial, Consumer<MonitorType> onChange,
                    Consumer<MonitorType> remember) {
        this.monitor = monitor;
        this.onChange = onChange;
        this.remember = remember;
        this.current = initial;
        comboBox = new JComboBox<>(monitor.choices);
        comboBox.setFocusable(false);
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, ((MonitorType) value).label(),
                    index, isSelected, cellHasFocus);
            }
        });
        comboBox.setMaximumSize(comboBox.getPreferredSize()); // keep the toolbar from stretching it
        comboBox.setSelectedItem(current);
        comboBox.addActionListener(e -> apply((MonitorType) comboBox.getSelectedItem()));
    }

    /** Adds a caption and the drop-down to the end of a toolbar, set apart from what's already there. */
    void addTo(JToolBar toolbar) {
        toolbar.addSeparator();
        JLabel caption = new JLabel(monitor.caption);
        caption.setLabelFor(comboBox);
        toolbar.add(caption);
        toolbar.add(comboBox);
    }

    /** @param enabled false while the screen this controls isn't shown */
    void setEnabled(boolean enabled) {
        comboBox.setEnabled(enabled);
    }

    private void apply(MonitorType type) {
        if (type != null && type != current) {
            current = type;
            remember.accept(type);
            onChange.accept(type);
        }
    }

    /**
     * The type last chosen for a screen, for a newly built machine.
     *
     * @param monitor the screen
     * @return the type last chosen, or the screen's default if none was (or what was
     *         remembered is no longer a type this build knows, or one that screen offers)
     */
    static MonitorType remembered(Monitor monitor) {
        try {
            MonitorType type = MonitorType.valueOf(PREFS.get(monitor.key, monitor.defaultType.name()));
            for (MonitorType choice : monitor.choices) {
                if (choice == type) {
                    return type;
                }
            }
        } catch (IllegalArgumentException e) {
            // fall through to the default
        }
        return monitor.defaultType;
    }

    /** Package-visible for tests: the drop-down itself. */
    JComboBox<MonitorType> comboBox() {
        return comboBox;
    }
}
