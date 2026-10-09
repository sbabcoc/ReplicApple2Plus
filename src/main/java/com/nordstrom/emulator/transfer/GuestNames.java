package com.nordstrom.emulator.transfer;

import java.util.Locale;

/**
 * Suggests guest names that satisfy an adapter's declared rules -- case,
 * length, allowed characters, first-letter rule (TRANSFER-CARD.md 4.3).
 * Only a suggestion: the user may edit it, and the adapter has the final
 * word (it answers BAD_NAME).
 */
public final class GuestNames {

    private GuestNames() {
    }

    /**
     * @param wanted the name wanted, e.g. from a host file name
     * @param caps   the adapter's capabilities
     * @return a name the guest's rules allow
     */
    public static String suggest(String wanted, Capabilities caps) {
        String s = caps.upperCaseOnly() ? wanted.toUpperCase(Locale.ROOT) : wanted;
        String extra = caps.nameExtraChars();
        char replacement = extra.isEmpty() ? 0 : extra.charAt(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean alphanumeric = c < 0x80 && Character.isLetterOrDigit(c);
            if (alphanumeric || extra.indexOf(c) >= 0) {
                sb.append(c);
            } else if (replacement != 0) {
                sb.append(replacement);
            }
        }
        if (sb.isEmpty()) {
            sb.append(caps.upperCaseOnly() ? "UNTITLED" : "untitled");
        }
        if (caps.startsWithLetter() && !isAsciiLetter(sb.charAt(0))) {
            sb.insert(0, caps.upperCaseOnly() ? 'A' : 'a');
        }
        int max = Math.max(1, caps.maxNameLength());
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
