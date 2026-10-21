package com.nordstrom.emulator;

import com.nordstrom.emulator.system.ShiftKeyMod;

import java.util.prefs.Preferences;

/**
 * The Shift Key Mod's wiring as chosen in Settings, remembered with the
 * JDK's user preferences so every machine built later -- after a Reboot, or
 * in the next launch -- is wired the same way. The default is
 * {@link ShiftKeyMod.Wiring#SW2}, MAME's default.
 */
final class ShiftKeyModSetting {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ShiftKeyModSetting.class);
    private static final String SHIFT_KEY_MOD_KEY = "shiftKeyMod";
    private static final ShiftKeyMod.Wiring DEFAULT_WIRING = ShiftKeyMod.Wiring.SW2;

    private ShiftKeyModSetting() {}

    /**
     * @return the wiring last chosen, or the default if none was
     *         (or what was remembered is no longer a wiring this build knows)
     */
    static ShiftKeyMod.Wiring remembered() {
        try {
            return ShiftKeyMod.Wiring.valueOf(PREFS.get(SHIFT_KEY_MOD_KEY, DEFAULT_WIRING.name()));
        } catch (IllegalArgumentException e) {
            return DEFAULT_WIRING;
        }
    }

    /** @param wiring the wiring for every machine built from now on */
    static void remember(ShiftKeyMod.Wiring wiring) {
        PREFS.put(SHIFT_KEY_MOD_KEY, wiring.name());
    }
}
