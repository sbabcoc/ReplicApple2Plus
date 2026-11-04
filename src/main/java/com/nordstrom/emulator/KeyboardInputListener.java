package com.nordstrom.emulator;

import com.nordstrom.emulator.system.ShiftKeyMod;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.concurrent.Executor;

/**
 * Feeds real Swing keyboard events to the keyboard connector, via
 * {@link KeyboardMapper} for the actual translation. Keys go through the
 * device on the keyboard cable ({@link CommandRecall}, a
 * {@link KeyboardPort}), as they would with it plugged in between the
 * keyboard and the motherboard, so it knows when a key is waiting. This class is pure plumbing -- it owns no mapping logic
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

    private final CommandRecall device;
    private final ShiftKeyMod shiftKeyMod;
    private final Executor emulationThread;

    /**
     * @param device      the device on the keyboard cable: keystrokes go through it,
     *                    and it's told when Up or Down is pressed
     * @param shiftKeyMod told when Shift or Control is pressed or released
     * @param emulationThread runs each delivery on the emulation thread --
     *                        AWT calls this listener on the event thread, which
     *                        must not touch emulator state directly
     */
    KeyboardInputListener(CommandRecall device, ShiftKeyMod shiftKeyMod, Executor emulationThread) {
        this.device = device;
        this.shiftKeyMod = shiftKeyMod;
        this.emulationThread = emulationThread;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        int mapped = KeyboardMapper.mapTypedCharacter(e.getKeyChar());
        if (mapped >= 0) {
            emulationThread.execute(() -> device.send(mapped));
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        reportModifier(e.getKeyCode(), true);
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> {
                emulationThread.execute(device::older);
                return;
            }
            case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> {
                emulationThread.execute(device::newer);
                return;
            }
            default -> { }
        }
        int mapped = KeyboardMapper.mapSpecialKey(e.getKeyCode(), e.isControlDown());
        if (mapped >= 0) {
            emulationThread.execute(() -> device.send(mapped));
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
