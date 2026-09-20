package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks in KeyboardMapper's ASCII mapping, uppercase-forcing, and Ctrl-combination rules. */
class KeyboardMapperTest {

    @Test
    void lowercaseIsForcedToUppercase() {
        assertEquals(0x41, KeyboardMapper.mapTypedCharacter('a'));
        assertEquals(0x41, KeyboardMapper.mapTypedCharacter('A'));
    }

    @Test
    void printableCharactersMapToThemselves() {
        assertEquals(0x35, KeyboardMapper.mapTypedCharacter('5'));
        assertEquals(0x20, KeyboardMapper.mapTypedCharacter(' '));
        assertEquals('!', KeyboardMapper.mapTypedCharacter('!'));
    }

    @Test
    void nonPrintableCharactersAreUnmapped() {
        assertEquals(-1, KeyboardMapper.mapTypedCharacter((char) 0x01));
        assertEquals(-1, KeyboardMapper.mapTypedCharacter((char) 0x7F));
    }

    @Test
    void specialKeysMapCorrectly() {
        assertEquals(0x0D, KeyboardMapper.mapSpecialKey(KeyEvent.VK_ENTER, false));
        assertEquals(0x08, KeyboardMapper.mapSpecialKey(KeyEvent.VK_LEFT, false));
        assertEquals(0x08, KeyboardMapper.mapSpecialKey(KeyEvent.VK_BACK_SPACE, false));
        assertEquals(0x1B, KeyboardMapper.mapSpecialKey(KeyEvent.VK_ESCAPE, false));
        assertEquals(-1, KeyboardMapper.mapSpecialKey(KeyEvent.VK_F1, false));
    }

    @Test
    void ctrlCombinationsMapToStandardControlCodes() {
        assertEquals(0x01, KeyboardMapper.mapSpecialKey(KeyEvent.VK_A, true));
        assertEquals(0x03, KeyboardMapper.mapSpecialKey(KeyEvent.VK_C, true));
        assertEquals(0x1A, KeyboardMapper.mapSpecialKey(KeyEvent.VK_Z, true));
    }

    @Test
    void letterKeyWithoutCtrlIsUnmappedBySpecialKeyHandler() {
        // Plain letters are handled by mapTypedCharacter, not mapSpecialKey
        assertEquals(-1, KeyboardMapper.mapSpecialKey(KeyEvent.VK_A, false));
    }
}
