package com.nordstrom.emulator;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.ShiftKeyMod;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A capital typed on a keyboard with no Shift key of its own -- Android's
 * on-screen keyboard sends a capital as a capital -- still reaches the
 * shift-key mod: Shift is held for it until the program has taken the key,
 * and a moment after. The accesses here are the CPU's, made directly on the
 * device between the CPU and the motherboard; no ROMs are needed.
 */
class TypedShiftTest {

    private static final int SHIFT_WIRE = 0xC063;   // SW2
    private static final int HELD = 0x00;
    private static final int UP = 0x80;

    private final MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8]);
    private final CommandRecall recall =
        new CommandRecall(bus, bus.keyboardRegister(), new CommandHistory(), bus.shiftKeyMod());

    TypedShiftTest() {
        bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW2);
    }

    @AfterEach
    void close() {
        bus.close();
    }

    /** Takes the key as a program does: reads it, then clears the strobe. */
    private int takeKey() {
        int key = recall.read(0xC000);
        recall.read(0xC010);
        return key;
    }

    private void idle(int accesses) {
        for (int i = 0; i < accesses; i++) {
            recall.read(0x0300);
        }
    }

    @Test
    void aCapitalHoldsShiftUntilAfterItsKeyIsTaken() {
        assertEquals(UP, recall.read(SHIFT_WIRE));
        recall.sendShifted('A');
        assertEquals(HELD, recall.read(SHIFT_WIRE), "Shift is down before the key is read");
        idle(1_000_000);
        assertEquals(HELD, recall.read(SHIFT_WIRE), "and stays down while the key waits");
        assertEquals(0xC1, takeKey());
        assertEquals(HELD, recall.read(SHIFT_WIRE), "a program reading Shift just after taking the key sees it");
        idle(CommandRecall.SHIFT_RELEASE_ACCESSES);
        assertEquals(UP, recall.read(SHIFT_WIRE), "then Shift lets go");
    }

    @Test
    void theNextKeyLetsGoOfShiftAtOnce() {
        recall.sendShifted('A');
        takeKey();
        recall.send('B');                               // a lowercase b, typed quickly after
        assertEquals(UP, recall.read(SHIFT_WIRE), "the next key isn't shifted");
    }

    @Test
    void theHostsOwnShiftKeyStillHoldsTheWire() {
        bus.shiftKeyMod().setShiftHeld(true);           // a physical keyboard's Shift, held
        recall.sendShifted('A');
        takeKey();
        idle(CommandRecall.SHIFT_RELEASE_ACCESSES);
        assertEquals(HELD, recall.read(SHIFT_WIRE), "the typed Shift letting go doesn't release the real one");
        bus.shiftKeyMod().setShiftHeld(false);
        assertEquals(UP, recall.read(SHIFT_WIRE));
    }

    @Test
    void theKeyboardSendsCapitalsShiftedAndLowercaseNot() {
        KeyboardInputListener listener = new KeyboardInputListener(recall, bus.shiftKeyMod(), Runnable::run);
        JPanel source = new JPanel();
        listener.keyTyped(new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'H'));
        assertEquals(HELD, recall.read(SHIFT_WIRE), "a capital, with no Shift key event at all");
        assertEquals(0xC8, takeKey());
        listener.keyTyped(new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'i'));
        assertEquals(UP, recall.read(SHIFT_WIRE), "lowercase goes without Shift");
        assertEquals(0xC9, takeKey(), "both reach the II+ keyboard as uppercase, as the real one sends them");
    }
}
