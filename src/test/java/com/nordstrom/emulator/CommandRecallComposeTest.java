package com.nordstrom.emulator;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The characters the II+ keyboard has no keys for, put on the keyboard
 * connector by the device on the keyboard cable: composed from Ctrl-V
 * sequences typed on the keyboard, or supplied from host keys. Read back
 * as the CPU reads them, from {@code $C000} through the device.
 */
class CommandRecallComposeTest {

    private final MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8]);
    private final CommandRecall device =
            new CommandRecall(bus, bus.keyboardRegister(), new CommandHistory(), bus.shiftKeyMod());

    @Test
    void ctrlVThenAKeySendsTheCharacterItStandsFor() {
        String keys = "()/-'<>!^";
        String sent = "[]\\_`{}|~";
        for (int i = 0; i < keys.length(); i++) {
            device.keyboardKey(CommandRecall.COMPOSE);
            device.keyboardKey(keys.charAt(i));
            assertEquals(0x80 | sent.charAt(i), takeKey(), "Ctrl-V " + keys.charAt(i));
        }
    }

    @Test
    void theCtrlVIsHeldBackUntilTheNextKey() {
        device.keyboardKey(CommandRecall.COMPOSE);
        assertFalse(device.keyWaiting());
        assertEquals(0, bus.read(0xC000) & 0x80);
    }

    @Test
    void ctrlVTwiceSendsCtrlV() {
        device.keyboardKey(CommandRecall.COMPOSE);
        device.keyboardKey(CommandRecall.COMPOSE);
        assertEquals(0x80 | CommandRecall.COMPOSE, takeKey());
    }

    @Test
    void anyOtherKeyAfterCtrlVSendsItself() {
        device.keyboardKey(CommandRecall.COMPOSE);
        device.keyboardKey('Q');
        assertEquals(0x80 | 'Q', takeKey());
        device.keyboardKey('(');
        assertEquals(0x80 | '(', takeKey()); // composing ended with the Q
    }

    @Test
    void everyCharacterTheKeyboardLacksHasASequenceThatSendsIt() {
        for (char c : "[\\_`{|}~".toCharArray()) {
            assertFalse(KeyboardMapper.keyboardHasKey(c), String.valueOf(c));
            int key = CommandRecall.composeKey(c);
            assertTrue(key >= 0 && KeyboardMapper.keyboardHasKey(key), "a typable key for " + c);
            device.keyboardKey(CommandRecall.COMPOSE);
            device.keyboardKey(key);
            assertEquals(0x80 | c, takeKey(), "Ctrl-V " + (char) key);
        }
    }

    @Test
    void codesNoSequenceSendsHaveNoComposeKey() {
        assertEquals(-1, CommandRecall.composeKey('A'));
        assertEquals(-1, CommandRecall.composeKey(CommandRecall.COMPOSE));
    }

    @Test
    void typedKeysAreNeverComposed() {
        device.send(CommandRecall.COMPOSE);
        assertTrue(device.keyWaiting());
        assertEquals(0x80 | CommandRecall.COMPOSE, takeKey());
        device.send('(');
        assertEquals(0x80 | '(', takeKey());
    }

    /** Reads {@code $C000} and clears the strobe, as software takes a key. */
    private int takeKey() {
        int key = device.read(0xC000);
        device.read(0xC010);
        return key;
    }
}
