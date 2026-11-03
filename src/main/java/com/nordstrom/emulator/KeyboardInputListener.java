package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;
import com.nordstrom.emulator.system.ShiftKeyMod;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.concurrent.Executor;

/**
 * Feeds real Swing keyboard events into the emulated
 * {@link KeyboardRegister}, via {@link KeyboardMapper} for the actual
 * translation. This class is pure plumbing -- it owns no mapping logic
 * of its own -- so that the mapping rules stay in one place, already
 * tested independently of any real window or event dispatch thread.
 * <p>
 * Also reports the Shift and Control keys' held state to the
 * {@link ShiftKeyMod}, which is the only way either reaches software:
 * the II+ keyboard itself never sends it.
 * <p>
 * The Up and Down arrows, which the II+ keyboard doesn't have and
 * {@link KeyboardMapper} leaves unmapped, step through command history
 * ({@link CommandRecall}); away from a command prompt they still do nothing.
 */
final class KeyboardInputListener implements KeyListener {

    private final KeyboardRegister keyboardRegister;
    private final ShiftKeyMod shiftKeyMod;
    private final CommandRecall recall;
    private final Executor emulationThread;

    /**
     * @param keyboardRegister the register keystrokes are delivered to
     * @param shiftKeyMod told when Shift or Control is pressed or released
     * @param recall told when Up or Down is pressed
     * @param emulationThread runs each delivery on the emulation thread --
     *                        AWT calls this listener on the event thread, which
     *                        must not touch emulator state directly
     */
    KeyboardInputListener(KeyboardRegister keyboardRegister, ShiftKeyMod shiftKeyMod, CommandRecall recall,
                          Executor emulationThread) {
        this.keyboardRegister = keyboardRegister;
        this.shiftKeyMod = shiftKeyMod;
        this.recall = recall;
        this.emulationThread = emulationThread;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        int mapped = KeyboardMapper.mapTypedCharacter(e.getKeyChar());
        if (mapped >= 0) {
            emulationThread.execute(() -> keyboardRegister.keyPressed(mapped));
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        reportModifier(e.getKeyCode(), true);
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> {
                emulationThread.execute(recall::older);
                return;
            }
            case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> {
                emulationThread.execute(recall::newer);
                return;
            }
            default -> { }
        }
        int mapped = KeyboardMapper.mapSpecialKey(e.getKeyCode(), e.isControlDown());
        if (mapped >= 0) {
            emulationThread.execute(() -> keyboardRegister.keyPressed(mapped));
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Real Apple II+ hardware has no notion of key-up for the keyboard
        // register -- the strobe simply reflects the last key pressed until
        // $C010 clears it. Only the shift-key mod's wires see a release.
        reportModifier(e.getKeyCode(), false);
    }

    private void reportModifier(int keyCode, boolean held) {
        if (keyCode == KeyEvent.VK_SHIFT) {
            emulationThread.execute(() -> shiftKeyMod.setShiftHeld(held));
        } else if (keyCode == KeyEvent.VK_CONTROL) {
            emulationThread.execute(() -> shiftKeyMod.setControlHeld(held));
        }
    }
}
