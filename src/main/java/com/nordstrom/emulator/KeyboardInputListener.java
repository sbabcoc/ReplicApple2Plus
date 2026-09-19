package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

/**
 * Feeds real Swing keyboard events into the emulated
 * {@link KeyboardRegister}, via {@link KeyboardMapper} for the actual
 * translation. This class is pure plumbing -- it owns no mapping logic
 * of its own -- so that the mapping rules stay in one place, already
 * tested independently of any real window or event dispatch thread.
 */
final class KeyboardInputListener implements KeyListener {

    private final KeyboardRegister keyboardRegister;

    KeyboardInputListener(KeyboardRegister keyboardRegister) {
        this.keyboardRegister = keyboardRegister;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        int mapped = KeyboardMapper.mapTypedCharacter(e.getKeyChar());
        if (mapped >= 0) {
            keyboardRegister.keyPressed(mapped);
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int mapped = KeyboardMapper.mapSpecialKey(e.getKeyCode(), e.isControlDown());
        if (mapped >= 0) {
            keyboardRegister.keyPressed(mapped);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Real Apple II+ hardware has no notion of key-up at all -- the
        // strobe simply reflects the last key pressed until $C010 clears
        // it. Nothing to do here.
    }
}
