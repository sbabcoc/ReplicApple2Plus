package com.nordstrom.emulator;

import com.nordstrom.emulator.system.ShiftKeyMod;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;

import java.util.concurrent.Executor;
import java.util.prefs.Preferences;

/**
 * Builds the "Machine" menu: modifications wired into the motherboard
 * itself, as opposed to cards in slots. Holds one choice so far, the
 * {@link ShiftKeyMod}'s wiring, as a "Shift Key Mod" submenu with one
 * radio item per wiring, named as MAME's machine configuration names
 * them.
 * <p>
 * The choice takes effect at once -- the running machine is rewired on
 * the emulation thread -- and is remembered with the JDK's user
 * preferences (as {@link DiskMenu} remembers its last folder), so it
 * carries across Reboot and across launches. With nothing remembered the
 * wiring is {@link ShiftKeyMod.Wiring#SW2}, MAME's default.
 */
final class MachineMenu {

    private static final Preferences PREFS = Preferences.userNodeForPackage(MachineMenu.class);
    private static final String SHIFT_KEY_MOD_KEY = "shiftKeyMod";
    private static final ShiftKeyMod.Wiring DEFAULT_WIRING = ShiftKeyMod.Wiring.SW2;

    private MachineMenu() {}

    /**
     * The remembered wiring, for a newly built machine.
     *
     * @return the wiring last chosen, or {@link #DEFAULT_WIRING} if none was
     *         (or what was remembered is no longer a wiring this build knows)
     */
    static ShiftKeyMod.Wiring rememberedWiring() {
        String remembered = PREFS.get(SHIFT_KEY_MOD_KEY, DEFAULT_WIRING.name());
        try {
            return ShiftKeyMod.Wiring.valueOf(remembered);
        } catch (IllegalArgumentException e) {
            return DEFAULT_WIRING;
        }
    }

    /**
     * Builds the menu for one machine.
     *
     * @param shiftKeyMod the machine's shift-key modification
     * @param current the wiring it was built with
     * @param emulationThread runs the rewiring on the emulation thread
     * @return the menu
     */
    static JMenu build(ShiftKeyMod shiftKeyMod, ShiftKeyMod.Wiring current, Executor emulationThread) {
        JMenu shiftMenu = new JMenu("Shift Key Mod");
        ButtonGroup group = new ButtonGroup();
        for (ShiftKeyMod.Wiring wiring : ShiftKeyMod.Wiring.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(wiring.label(), wiring == current);
            item.addActionListener(e -> {
                PREFS.put(SHIFT_KEY_MOD_KEY, wiring.name());
                emulationThread.execute(() -> shiftKeyMod.setWiring(wiring));
            });
            group.add(item);
            shiftMenu.add(item);
        }
        JMenu menu = new JMenu("Machine");
        menu.add(shiftMenu);
        return menu;
    }
}
