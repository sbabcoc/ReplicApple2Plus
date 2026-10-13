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

    /**
     * Whether a name satisfies an adapter's declared rules exactly as it is.
     *
     * @param name the name
     * @param caps the adapter's capabilities
     * @return true if the guest would accept it unchanged
     */
    public static boolean isValid(String name, Capabilities caps) {
        if (name.isEmpty()) {
            return false;
        }
        if (caps.startsWithLetter() && !isAsciiLetter(name.charAt(0))) {
            return false;
        }
        String extra = caps.nameExtraChars();
        int dots = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (caps.twoPartNames() && c == '.') {
                dots++;
                continue;
            }
            boolean alphanumeric = c < 0x80 && Character.isLetterOrDigit(c);
            if (!(alphanumeric || extra.indexOf(c) >= 0)) {
                return false;
            }
            if (caps.upperCaseOnly() && Character.isLowerCase(c)) {
                return false;
            }
        }
        if (!caps.twoPartNames()) {
            return name.length() <= caps.maxNameLength();
        }
        int dot = name.indexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        return dots <= 1 && !base.isEmpty() && base.length() <= caps.nameBaseMax()
            && (dot < 0 || !extension.isEmpty()) && extension.length() <= caps.nameExtensionMax();
    }

    /**
     * A wanted name as the guest would hold it, changed only by case -- upper
     * case, where the guest's names are upper case only, as CP/M's CCP does --
     * or null if it would need any other change. A null answer means the
     * user must choose the guest name: it is never shortened or altered
     * behind their back (TRANSFER-CARD.md 8.4).
     *
     * @param wanted the name wanted, e.g. from a host file name
     * @param caps   the adapter's capabilities
     * @return the name, or null if the guest's rules don't allow it
     */
    public static String acceptable(String wanted, Capabilities caps) {
        String name = caps.upperCaseOnly() ? wanted.toUpperCase(Locale.ROOT) : wanted;
        return isValid(name, caps) ? name : null;
    }

    /**
     * The adapter's name rules in words, for telling the user why a name
     * can't be used.
     *
     * @param caps the adapter's capabilities
     * @return e.g. "1-8 characters, optionally a dot and 1-3 more; letters,
     *         digits and !#$%"
     */
    public static String describeRules(Capabilities caps) {
        StringBuilder sb = new StringBuilder();
        if (caps.twoPartNames()) {
            sb.append("1-").append(caps.nameBaseMax()).append(" characters, optionally a dot and 1-")
                .append(caps.nameExtensionMax()).append(" more");
        } else {
            sb.append("1-").append(caps.maxNameLength()).append(" characters");
        }
        if (caps.startsWithLetter()) {
            sb.append(", starting with a letter");
        }
        sb.append("; letters, digits");
        String extra = caps.nameExtraChars();
        if (!extra.isEmpty()) {
            sb.append(" and ").append(extra);
        }
        return sb.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
