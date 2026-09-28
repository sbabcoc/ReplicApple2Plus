package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.concurrent.Executor;

/**
 * Feeds real Swing keyboard events into the emulated
 * {@link KeyboardRegister}, via {@link KeyboardMapper} for the actual
 * translation. This class is pure plumbing -- it owns no mapping logic
 * of its own -- so that the mapping rules stay in one place, already
 * tested independently of any real window or event dispatch thread.
 */
final class KeyboardInputListener implements KeyListener {

    private final KeyboardRegister keyboardRegister;
    private final Executor emulationThread;

    /**
     * @param keyboardRegister the register keystrokes are delivered to
     * @param emulationThread runs each delivery on the emulation thread --
     *                        AWT calls this listener on the event thread, which
     *                        must not touch emulator state directly
     */
    KeyboardInputListener(KeyboardRegister keyboardRegister, Executor emulationThread) {
        this.keyboardRegister = keyboardRegister;
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
        int mapped = KeyboardMapper.mapSpecialKey(e.getKeyCode(), e.isControlDown());
        if (mapped >= 0) {
            emulationThread.execute(() -> keyboardRegister.keyPressed(mapped));
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Real Apple II+ hardware has no notion of key-up at all -- the
        // strobe simply reflects the last key pressed until $C010 clears
        // it. Nothing to do here.
    }
}
