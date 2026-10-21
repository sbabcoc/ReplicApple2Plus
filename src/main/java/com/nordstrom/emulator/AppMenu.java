package com.nordstrom.emulator;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Desktop;

/**
 * The application-level commands -- About ReplicApple2Plus, Settings...
 * and Quit -- placed where each platform expects them, while doing the
 * same thing everywhere.
 * <p>
 * Where the platform has an application menu of its own (macOS), these
 * commands belong there, and only there: the OS always shows About and
 * Quit in it, so adding them to our own menus too would list them twice.
 * {@link #install} fills those system items in. Everywhere else there is
 * no application menu, so File gets Settings... and Quit, and a Help menu
 * gets About -- the same placement on Windows and Linux. Each command is
 * checked separately, by the {@link Desktop.Action} that hands it to the
 * system menu, not by guessing the platform from its name.
 * <p>
 * Quit is {@code System.exit(0)}: the same exit as closing the main window,
 * so the shutdown hook in {@code Apple2Plus} stops the machine and saves
 * any disk writes either way. macOS's own Quit already exits the same way,
 * so no quit handler is installed.
 */
final class AppMenu {

    private static final String NAME = "ReplicApple2Plus";
    private static final String PROJECT_URL = "https://github.com/sbabcoc/ReplicApple2Plus";

    private AppMenu() {}

    /** @return true if About is in the system's application menu, not ours */
    static boolean systemHasAbout() {
        return supports(Desktop.Action.APP_ABOUT);
    }

    /** @return true if Settings... is in the system's application menu, not ours */
    static boolean systemHasSettings() {
        return supports(Desktop.Action.APP_PREFERENCES);
    }

    /** @return true if Quit is in the system's application menu, not ours */
    static boolean systemHasQuit() {
        return supports(Desktop.Action.APP_QUIT_HANDLER);
    }

    private static boolean supports(Desktop.Action action) {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action);
    }

    /**
     * Wires the system application menu's About and Settings... items, where
     * there is one. Called once: the handlers are application-wide, and
     * outlive any one machine.
     *
     * @param parent  anchors the dialogs (the main window)
     * @param startup how the emulator was started, for the Settings dialog
     */
    static void install(Component parent, Apple2Plus.StartupFiles startup) {
        if (systemHasAbout()) {
            Desktop.getDesktop().setAboutHandler(event -> SwingUtilities.invokeLater(() -> showAbout(parent)));
        }
        if (systemHasSettings()) {
            Desktop.getDesktop().setPreferencesHandler(event ->
                SwingUtilities.invokeLater(() -> SettingsDialog.show(parent, startup)));
        }
    }

    /**
     * @param parent anchors the About box
     * @return a Help menu holding About, or null where About is in the system's application menu
     */
    static JMenu buildHelpMenu(Component parent) {
        if (systemHasAbout()) {
            return null;
        }
        JMenu menu = new JMenu("Help");
        JMenuItem about = new JMenuItem("About " + NAME);
        about.addActionListener(event -> showAbout(parent));
        menu.add(about);
        return menu;
    }

    /** Exits, through the same shutdown hook as closing the main window. */
    static void quit() {
        System.exit(0);
    }

    static void showAbout(Component parent) {
        String version = AppMenu.class.getPackage().getImplementationVersion();
        String text = NAME + "\n"
            + (version != null ? "Version " + version : "Development build") + "\n\n"
            + "A from-scratch Apple II+ emulator, written in Java.\n"
            + PROJECT_URL + "\n"
            + "Licensed under the Apache License, Version 2.0.\n\n"
            + "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
        JOptionPane.showMessageDialog(parent, text, "About " + NAME, JOptionPane.PLAIN_MESSAGE);
    }
}
