package com.nordstrom.emulator.transfer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a guest OS adapter declared about its file system in BEGIN -- the
 * emulator's only knowledge of the guest OS (TRANSFER-CARD.md 4.3). Read
 * from the extensible encoding of section 5.2: tagged, length-prefixed
 * fields, with unknown tags skipped so later fields break nothing here.
 */
public final class Capabilities {

    private String osName = "";
    private String osVersion = "";
    private boolean hierarchical;
    private int maxNameLength = 15;
    private int nameRules;
    private String nameExtraChars = "";
    private byte[] textLineEnd = {0x0D};
    private boolean textHighBit;
    private List<String> textTags = List.of();
    private FileType defaultTextType = new FileType("TXT", 0);
    private FileType defaultBinaryType = new FileType("BIN", 0);
    private int defaultAttributes;
    private boolean textAuxIsRecordLength;

    private Capabilities() {
    }

    /**
     * Reads a capability record.
     *
     * @param bytes the record, from its first field to its end tag
     * @return the capabilities
     * @throws IllegalArgumentException if a field runs past the end
     */
    public static Capabilities parse(byte[] bytes) {
        Capabilities caps = new Capabilities();
        int pos = 0;
        while (pos < bytes.length) {
            int tag = bytes[pos++] & 0xFF;
            if (tag == Protocol.CAP_END) {
                break;
            }
            if (pos >= bytes.length) {
                throw new IllegalArgumentException("capability field $" + Integer.toHexString(tag) + " has no length");
            }
            int length = bytes[pos++] & 0xFF;
            if (pos + length > bytes.length) {
                throw new IllegalArgumentException("capability field $" + Integer.toHexString(tag) + " runs past the end");
            }
            byte[] value = java.util.Arrays.copyOfRange(bytes, pos, pos + length);
            pos += length;
            caps.apply(tag, value);
        }
        return caps;
    }

    private void apply(int tag, byte[] value) {
        switch (tag) {
            case Protocol.CAP_OS_NAME -> osName = ascii(value);
            case Protocol.CAP_OS_VERSION -> osVersion = ascii(value);
            case Protocol.CAP_STRUCTURE -> hierarchical = value.length > 0 && value[0] != 0;
            case Protocol.CAP_NAME_MAX_LENGTH -> maxNameLength = value.length > 0 ? value[0] & 0xFF : maxNameLength;
            case Protocol.CAP_NAME_RULES -> nameRules = value.length > 0 ? value[0] & 0xFF : 0;
            case Protocol.CAP_NAME_EXTRA_CHARS -> nameExtraChars = ascii(value);
            case Protocol.CAP_TEXT_LINE_END -> textLineEnd = value.clone();
            case Protocol.CAP_TEXT_HIGH_BIT -> textHighBit = value.length > 0 && value[0] != 0;
            case Protocol.CAP_TEXT_TAGS -> textTags = strings(value);
            case Protocol.CAP_DEFAULT_TEXT_TYPE -> defaultTextType = fileType(value);
            case Protocol.CAP_DEFAULT_BINARY_TYPE -> defaultBinaryType = fileType(value);
            case Protocol.CAP_DEFAULT_ATTRIBUTES -> defaultAttributes = value.length > 0 ? value[0] & 0xFF : 0;
            case Protocol.CAP_TEXT_AUX_IS_RECORD_LENGTH -> textAuxIsRecordLength = value.length > 0 && value[0] != 0;
            default -> {
                // unknown: a field from a later protocol revision -- skipped by design
            }
        }
    }

    private static String ascii(byte[] value) {
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static List<String> strings(byte[] value) {
        List<String> out = new ArrayList<>();
        int pos = 0;
        while (pos < value.length) {
            int length = value[pos++] & 0xFF;
            out.add(new String(value, pos, Math.min(length, value.length - pos), StandardCharsets.US_ASCII));
            pos += length;
        }
        return Collections.unmodifiableList(out);
    }

    private static FileType fileType(byte[] value) {
        int length = value[0] & 0xFF;
        String tag = new String(value, 1, length, StandardCharsets.US_ASCII);
        int aux = (value[1 + length] & 0xFF) | (value[2 + length] & 0xFF) << 8;
        return new FileType(tag, aux);
    }

    /** @return the OS's name, for display only */
    public String osName() {
        return osName;
    }

    /** @return the OS's version, for display only */
    public String osVersion() {
        return osVersion;
    }

    /** @return true if directories nest; false for a flat file system per volume */
    public boolean hierarchical() {
        return hierarchical;
    }

    /** @return the longest name the guest accepts */
    public int maxNameLength() {
        return maxNameLength;
    }

    /** @return true if guest names are upper case only */
    public boolean upperCaseOnly() {
        return (nameRules & Protocol.NAME_UPPER_CASE_ONLY) != 0;
    }

    /** @return true if guest names must start with a letter */
    public boolean startsWithLetter() {
        return (nameRules & Protocol.NAME_STARTS_WITH_LETTER) != 0;
    }

    /** @return the non-alphanumeric characters allowed in guest names */
    public String nameExtraChars() {
        return nameExtraChars;
    }

    /** @return the byte sequence ending each line of guest text */
    public byte[] textLineEnd() {
        return textLineEnd.clone();
    }

    /** @return true if guest text has bit 7 set on every character */
    public boolean textHighBit() {
        return textHighBit;
    }

    /** @return the type tags that mean text */
    public List<String> textTags() {
        return textTags;
    }

    /**
     * @param tag a type tag
     * @return true if files of that type are text
     */
    public boolean isText(String tag) {
        return textTags.contains(tag);
    }

    /** @return the type given to an imported text file whose host name carries none */
    public FileType defaultTextType() {
        return defaultTextType;
    }

    /** @return the type given to any other imported file whose host name carries none */
    public FileType defaultBinaryType() {
        return defaultBinaryType;
    }

    /**
     * @param type a file's type
     * @return true if it's text whose lines may be converted: a text type,
     *         and not a random-access file (capability $0D)
     */
    public boolean isConvertibleText(FileType type) {
        return isText(type.tag()) && !(textAuxIsRecordLength && type.aux() != 0);
    }

    /** @return the attribute byte of a file with no special attributes */
    public int defaultAttributes() {
        return defaultAttributes;
    }

    /** Builds an encoded capability record -- for adapters written in Java, i.e. tests. */
    public static final class Builder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * Adds a field with a raw value.
         *
         * @param tag   the field's tag
         * @param value its value
         * @return this builder
         */
        public Builder field(int tag, byte... value) {
            out.write(tag);
            out.write(value.length);
            out.writeBytes(value);
            return this;
        }

        /**
         * Adds a field whose value is ASCII text.
         *
         * @param tag   the field's tag
         * @param value its value
         * @return this builder
         */
        public Builder field(int tag, String value) {
            return field(tag, value.getBytes(StandardCharsets.US_ASCII));
        }

        /**
         * Adds a field whose value is a sequence of length-prefixed strings.
         *
         * @param tag    the field's tag
         * @param values the strings
         * @return this builder
         */
        public Builder strings(int tag, String... values) {
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            for (String s : values) {
                v.write(s.length());
                v.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
            }
            return field(tag, v.toByteArray());
        }

        /**
         * Adds a field whose value is a file type.
         *
         * @param tag     the field's tag
         * @param typeTag the type's tag
         * @param aux     the type's aux value
         * @return this builder
         */
        public Builder fileType(int tag, String typeTag, int aux) {
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            v.write(typeTag.length());
            v.writeBytes(typeTag.getBytes(StandardCharsets.US_ASCII));
            v.write(aux & 0xFF);
            v.write(aux >> 8 & 0xFF);
            return field(tag, v.toByteArray());
        }

        /** @return the record, with its end tag */
        public byte[] build() {
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            copy.write(Protocol.CAP_END);
            return copy.toByteArray();
        }
    }
}
