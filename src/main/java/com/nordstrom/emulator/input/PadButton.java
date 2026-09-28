package com.nordstrom.emulator.input;

/**
 * Every button a pad can offer, in this emulator's own vocabulary
 * (loosely SDL's, but independent of any library, so the mapping code
 * and its tests never depend on one). These are also the names used in
 * the input configuration file.
 * <p>
 * The two triggers are exposed as buttons, not axes. The Apple II's
 * pushbuttons are digital, and the pad that motivated this reports its
 * triggers as plain 0.0 or 1.0 anyway. A provider turns an analog
 * trigger into a button by treating it as pressed past halfway.
 */
public enum PadButton {
    /** Face button A, the one SDL calls "A" (its position varies by pad layout). */
    A,
    /** Face button B. */
    B,
    /** Face button X. */
    X,
    /** Face button Y. */
    Y,
    /** The back / select / minus button. */
    BACK,
    /** The guide / home button. */
    GUIDE,
    /** The start / plus button. */
    START,
    /** Pressing the left stick in. */
    LEFT_STICK,
    /** Pressing the right stick in. */
    RIGHT_STICK,
    /** Left shoulder button. */
    LEFT_BUMPER,
    /** Right shoulder button. */
    RIGHT_BUMPER,
    /** Left trigger, as a button. */
    LEFT_TRIGGER,
    /** Right trigger, as a button. */
    RIGHT_TRIGGER,
    /** D-pad up. */
    DPAD_UP,
    /** D-pad down. */
    DPAD_DOWN,
    /** D-pad left. */
    DPAD_LEFT,
    /** D-pad right. */
    DPAD_RIGHT,
    /** A pad-specific extra button (a capture or share button, say). */
    MISC1
}
