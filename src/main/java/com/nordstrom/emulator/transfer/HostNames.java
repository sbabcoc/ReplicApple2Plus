package com.nordstrom.emulator.transfer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The host file naming convention, TRANSFER-CARD.md 4.4 and 5.2:
 * {@code NAME[#tag,aux[,attr]][.TXT]}, with characters that aren't legal in
 * host file names on every platform written as {@code %XX}. This convention
 * outlives every other choice in the card -- files exported with it must keep
 * importing faithfully -- so it changes only with great care.
 */
public final class HostNames {

    private HostNames() {
    }

    private static final String TEXT_SUFFIX = ".TXT";

    /**
     * The host name for a guest file being exported.
     *
     * @param guestName  the guest's name for the file
     * @param type       its type
     * @param attributes its attribute byte
     * @param caps       the adapter's capabilities
     * @return the host file name
     */
    public static String encode(String guestName, FileType type, int attributes, Capabilities caps) {
        String name = escape(guestName, false);
        boolean text = caps.isConvertibleText(type);
        if (text && type.equals(caps.defaultTextType()) && attributes == caps.defaultAttributes()) {
            return name + TEXT_SUFFIX;
        }
        StringBuilder sb = new StringBuilder(name).append('#').append(escape(type.tag(), true))
            .append(',').append(String.format("%04X", type.aux()));
        if (attributes != caps.defaultAttributes()) {
            sb.append(',').append(String.format("%02X", attributes));
        }
        if (text) {
            sb.append(TEXT_SUFFIX);
        }
        return sb.toString();
    }

    /**
     * What a host file name says about the guest file to create from it.
     *
     * @param guestName  the guest name, unescaped
     * @param type       the type the name carries, or null if it carries none
     * @param attributes the attribute byte the name carries, or -1 if none
     * @param text       true if the name marks the file as converted text
     */
    public record Decoded(String guestName, FileType type, int attributes, boolean text) {

        /**
         * The type to create the file with: the name's own, or else the
         * adapter's default for text (if the name or the content says text)
         * or for other files.
         *
         * @param caps          the adapter's capabilities
         * @param contentIsText true if the file's bytes are plain text
         * @return the type
         */
        public FileType typeOr(Capabilities caps, boolean contentIsText) {
            if (type != null) {
                return type;
            }
            return (text || contentIsText) ? caps.defaultTextType() : caps.defaultBinaryType();
        }

        /**
         * The attribute byte to create the file with: the name's own, or the default.
         *
         * @param caps the adapter's capabilities
         * @return the attribute byte
         */
        public int attributesOr(Capabilities caps) {
            return attributes >= 0 ? attributes : caps.defaultAttributes();
        }
    }

    /**
     * Reads a host file name being imported.
     *
     * @param hostName the host file name
     * @return what it says; a name the convention doesn't recognize is all name
     */
    public static Decoded decode(String hostName) {
        String rest = hostName;
        boolean text = false;
        if (rest.length() > TEXT_SUFFIX.length()
                && rest.toUpperCase(Locale.ROOT).endsWith(TEXT_SUFFIX)) {
            rest = rest.substring(0, rest.length() - TEXT_SUFFIX.length());
            text = true;
        }
        int hash = rest.indexOf('#');
        if (hash > 0) {
            String[] parts = rest.substring(hash + 1).split(",", -1);
            if (parts.length == 2 || parts.length == 3) {
                try {
                    String tag = unescape(parts[0]);
                    int aux = Integer.parseInt(parts[1], 16);
                    int attributes = parts.length == 3 ? Integer.parseInt(parts[2], 16) : -1;
                    if (!tag.isEmpty() && aux <= 0xFFFF && attributes <= 0xFF) {
                        return new Decoded(unescape(rest.substring(0, hash)), new FileType(tag, aux), attributes, text);
                    }
                } catch (IllegalArgumentException notTheConvention) {
                    // fall through: treat the whole thing as a name
                }
            }
        }
        return new Decoded(unescape(rest), null, -1, text);
    }

    /**
     * Escapes the characters that aren't safe in a host file name on every
     * platform, plus the convention's own {@code #} and {@code %} (and
     * {@code ,} within a tag), and a trailing space or period (Windows).
     */
    static String escape(String s, boolean inTag) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xFF;
            boolean last = i == bytes.length - 1;
            boolean unsafe = c < 0x20 || c >= 0x7F || "/\\:*?\"<>|#%".indexOf(c) >= 0
                || (inTag && c == ',') || (last && (c == ' ' || c == '.'));
            if (unsafe) {
                sb.append('%').append(String.format("%02X", c));
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

}
