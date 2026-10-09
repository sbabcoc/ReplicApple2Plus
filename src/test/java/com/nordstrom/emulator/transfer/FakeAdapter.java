package com.nordstrom.emulator.transfer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A guest OS adapter written in Java, for tests: it drives the card only
 * through its four registers, byte by byte, exactly as the 6502 firmware
 * will -- following TRANSFER-CARD.md 5.2 on its own, so the card is tested
 * against the specification rather than against itself. It serves an
 * in-memory file system: one volume, "/VOL", hierarchical.
 */
final class FakeAdapter {

    /** A file in the fake guest file system. */
    record GuestFile(String tag, int aux, int attributes, byte[] data) {
    }

    final HostTransferCard card;
    final Map<List<String>, GuestFile> files = new LinkedHashMap<>();
    final List<List<String>> directories = new ArrayList<>();
    int requestsServed;

    FakeAdapter(HostTransferCard card) {
        this.card = card;
        directories.add(List.of("VOL"));
    }

    // ---- register-level primitives ----

    private int readData() {
        return card.readIoSwitch(Protocol.REG_DATA);
    }

    private void writeData(int b) {
        card.writeIoSwitch(Protocol.REG_DATA, b & 0xFF);
    }

    private void writeData(byte[] bytes) {
        for (byte b : bytes) {
            writeData(b);
        }
    }

    void finish(int code) {
        card.writeIoSwitch(Protocol.REG_COMPLETE, code);
    }

    private String readString() {
        int length = readData();
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) readData();
        }
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    private List<String> readPath() {
        int count = readData();
        List<String> path = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            path.add(readString());
        }
        return path;
    }

    private void writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        writeData(b.length);
        writeData(b);
    }

    // ---- adapter behavior ----

    /** BEGIN: protocol version 1, then the capability record. */
    void begin(byte[] capabilityRecord) {
        writeData(1);
        writeData(capabilityRecord);
        finish(Protocol.MSG_BEGIN);
    }

    /** Polls once; serves the request if there is one. @return the opcode served, or 0 */
    int serveOne() {
        int op = card.readIoSwitch(Protocol.REG_REQUEST);
        switch (op) {
            case 0 -> {
                return 0;
            }
            case Protocol.REQ_VOLUMES -> {
                writeString("VOL");
                writeString("");
                finish(ResultCode.OK.code());
            }
            case Protocol.REQ_LIST -> {
                List<String> dir = readPath();
                if (!directories.contains(dir)) {
                    finish(ResultCode.NOT_FOUND.code());
                    break;
                }
                for (List<String> d : directories) {
                    if (d.size() == dir.size() + 1 && d.subList(0, dir.size()).equals(dir)) {
                        writeString(d.get(d.size() - 1));
                        writeData(1);
                        writeString("DIR");
                        writeData(0);
                        writeData(0);
                        writeData(0xE3);
                        writeData(new byte[] {0, 2, 0, 0});
                    }
                }
                for (var e : files.entrySet()) {
                    List<String> p = e.getKey();
                    if (p.size() == dir.size() + 1 && p.subList(0, dir.size()).equals(dir)) {
                        GuestFile f = e.getValue();
                        writeString(p.get(p.size() - 1));
                        writeData(0);
                        writeString(f.tag());
                        writeData(f.aux() & 0xFF);
                        writeData(f.aux() >> 8);
                        writeData(f.attributes());
                        int size = f.data().length;
                        writeData(new byte[] {(byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
                    }
                }
                writeString("");
                finish(ResultCode.OK.code());
            }
            case Protocol.REQ_READ -> {
                GuestFile f = files.get(readPath());
                if (f == null) {
                    finish(ResultCode.NOT_FOUND.code());
                    break;
                }
                writeData(f.data());
                finish(ResultCode.OK.code());
            }
            case Protocol.REQ_WRITE -> {
                List<String> path = readPath();
                String tag = readString();
                int aux = readData() | readData() << 8;
                int attributes = readData();
                int size = readData() | readData() << 8 | readData() << 16 | readData() << 24;
                byte[] data = new byte[size];
                for (int i = 0; i < size; i++) {
                    data[i] = (byte) readData();
                }
                if (files.containsKey(path)) {
                    finish(ResultCode.EXISTS.code());
                } else if (path.get(path.size() - 1).equals("FULL")) {
                    writeData(0x48); // the OS's own code, then a message
                    writeString("DISK FULL");
                    finish(ResultCode.OTHER.code());
                } else {
                    files.put(path, new GuestFile(tag, aux, attributes, data));
                    finish(ResultCode.OK.code());
                }
            }
            case Protocol.REQ_MAKE_DIR -> {
                directories.add(readPath());
                finish(ResultCode.OK.code());
            }
            case Protocol.REQ_DELETE -> {
                finish(files.remove(readPath()) != null ? ResultCode.OK.code() : ResultCode.NOT_FOUND.code());
            }
            case Protocol.REQ_END -> finish(ResultCode.OK.code());
            default -> finish(ResultCode.IO_ERROR.code());
        }
        requestsServed++;
        return op;
    }

    /** Polls until it has served one request. */
    int serveNext() {
        for (int i = 0; i < 10; i++) {
            int op = serveOne();
            if (op != 0) {
                return op;
            }
        }
        throw new AssertionError("no request arrived");
    }

    /** A ProDOS-like capability record. */
    static byte[] proDosLike() {
        return new Capabilities.Builder()
            .field(Protocol.CAP_OS_NAME, "PRODOS")
            .field(Protocol.CAP_OS_VERSION, "2.4")
            .field(Protocol.CAP_STRUCTURE, (byte) 1)
            .field(Protocol.CAP_NAME_MAX_LENGTH, (byte) 15)
            .field(Protocol.CAP_NAME_RULES, (byte) (Protocol.NAME_UPPER_CASE_ONLY | Protocol.NAME_STARTS_WITH_LETTER))
            .field(Protocol.CAP_NAME_EXTRA_CHARS, ".")
            .field(Protocol.CAP_TEXT_LINE_END, (byte) 0x0D)
            .field(Protocol.CAP_TEXT_HIGH_BIT, (byte) 0)
            .strings(Protocol.CAP_TEXT_TAGS, "TXT")
            .fileType(Protocol.CAP_DEFAULT_TEXT_TYPE, "TXT", 0)
            .fileType(Protocol.CAP_DEFAULT_BINARY_TYPE, "BIN", 0)
            .field(Protocol.CAP_DEFAULT_ATTRIBUTES, (byte) 0xE3)
            .field(Protocol.CAP_TEXT_AUX_IS_RECORD_LENGTH, (byte) 1)
            .build();
    }

    /** A DOS 3.3-like capability record: flat, high-bit text. */
    static byte[] dosLike() {
        return new Capabilities.Builder()
            .field(Protocol.CAP_OS_NAME, "DOS")
            .field(Protocol.CAP_STRUCTURE, (byte) 0)
            .field(Protocol.CAP_NAME_MAX_LENGTH, (byte) 30)
            .field(Protocol.CAP_TEXT_LINE_END, (byte) 0x0D)
            .field(Protocol.CAP_TEXT_HIGH_BIT, (byte) 1)
            .strings(Protocol.CAP_TEXT_TAGS, "T")
            .fileType(Protocol.CAP_DEFAULT_TEXT_TYPE, "T", 0)
            .fileType(Protocol.CAP_DEFAULT_BINARY_TYPE, "B", 0)
            .field(Protocol.CAP_DEFAULT_ATTRIBUTES, (byte) 0)
            .build();
    }
}
