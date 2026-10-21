package com.nordstrom.emulator.transfer;

import com.nordstrom.emulator.system.CardParameter;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SlotCardLoader;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * The host file transfer card: an invented peripheral through which a guest
 * OS adapter -- the card's firmware -- serves file requests from the host's
 * transfer window. The card knows no guest OS; it only moves the messages of
 * TRANSFER-CARD.md 5.2 between its registers and a {@link TransferSession}.
 * <p>
 * Registers, at {@code $C0n0}: {@code $0} read = next request ({@code END}
 * whenever no session is open); {@code $1} write = completion code ending the
 * adapter's message; {@code $2} = data port; {@code $3} read = protocol
 * version plus "host available" bit, write = ROM bank select; {@code $4}
 * write = a printed character, for the {@link #printSpool print spool};
 * {@code $5} = where the firmware has got to in typing a recipe, 0 when not
 * typing; {@code $6} = the listing capture's phase (0 idle, 1 typing, 2
 * capturing); {@code $7} write = trim the capture to its non-blank lines.
 * {@code $5} and {@code $6} are kept on the card because RAM powers up
 * random, and RESET clears them.
 * <p>
 * Beside them, {@code $E} and {@code $F} are a 6850 serial interface's
 * status/control and data registers, and the slot page identifies the card
 * as an Apple Communications Card. That is for CP/M on the Microsoft
 * SoftCard II (TRANSFER-CARD.md 8.2): its 6502 server reads such a card as
 * CP/M's reader device, and this card's reader delivers the CP/M adapter,
 * {@code XFER}, as Intel HEX followed by {@code ^Z}. The reader starts the
 * adapter over after each {@code ^Z} and on each 6850 master reset; there is
 * always a byte to read, and transmitted bytes are discarded.
 * <p>
 * {@code IN#n} starts a transfer session; {@code PR#n} prints.
 * <p>
 * Its firmware -- the guest OS adapter, built from {@code firmware/hostfiles}
 * with ca65 -- is a committed resource: 256 bytes for the card's own
 * {@code $Cn00} page, then 2K expansion ROM banks for {@code $C800-$CFFF},
 * selected through register {@code $3}.
 */
public final class HostTransferCard implements SlotCard {

    private static final String FIRMWARE = "hostfiles-firmware.rom";
    private static final String CPM_ADAPTER = "xfer.hex";
    private static final int CPM_END_OF_FILE = 0x1A; // ^Z
    private static final int SLOT_PAGE = 0x100;
    private static final int BANK_SIZE = 0x800;

    private final byte[] firmware = loadFirmware();
    private final byte[] reader = loadReader();
    private int readerPos;
    /** Set when CP/M's 6502 server initializes the 6850, at each cold boot; cleared by RESET. */
    private volatile boolean servedByCpm;

    private volatile TransferHost host; // set on the UI thread, read on the emulation thread
    private Path initialDirectory;

    private final PrintSpool printSpool = new PrintSpool();
    private TransferSession session;
    private final ByteArrayOutputStream fromAdapter = new ByteArrayOutputStream();
    private byte[] toAdapter = new byte[0];
    private int toAdapterPos;
    private byte[] stash = new byte[0];
    private int romBank;
    private int typing; // the firmware's place in a recipe; 0 = not typing
    private int phase;  // the listing capture: 0 idle, 1 typing the command, 2 capturing

    @Override
    public String getShortName() {
        return "hostfiles";
    }

    @Override
    public String getDisplayName() {
        return "Host File Transfer Card";
    }

    @Override
    public List<CardParameter> getParameters() {
        return List.of(CardParameter.folder("dir", "Start folder"));
    }

    /** {@code dir} (optional): the folder the host dialogs open in first; must exist. */
    @Override
    public void configure(Properties props) {
        String dir = props.getProperty("dir");
        if (dir != null) {
            Path path = SlotCardLoader.resolvePath(props, dir); // relative to the configuration file
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("hostfiles: dir is not a folder: " + dir);
            }
            initialDirectory = path;
        }
    }

    /** @return the folder the host dialogs open in first, or null for the platform's default */
    public Path initialDirectory() {
        return initialDirectory;
    }

    /** Slots 1-7: the card needs a slot ROM page, which slot 0 doesn't have. */
    @Override
    public Set<Integer> supportedSlots() {
        return Set.of(1, 2, 3, 4, 5, 6, 7);
    }

    /**
     * Connects the host side. Without one, the status register says no
     * host is available, and any session the adapter begins is refused.
     *
     * @param host the host side, or null for none
     */
    public void setHost(TransferHost host) {
        this.host = host;
    }

    @Override
    public int readRom(int offset) {
        return firmware[offset & 0xFF] & 0xFF;
    }

    @Override
    public boolean wantsExpansionRom() {
        return true;
    }

    /** The selected bank of the firmware's expansion ROM; a bank past the end reads $FF. */
    @Override
    public int readExpansionRom(int offset) {
        int index = SLOT_PAGE + romBank * BANK_SIZE + offset;
        return index < firmware.length ? firmware[index] & 0xFF : 0xFF;
    }

    /** The CP/M adapter's Intel HEX, then ^Z: what CP/M's reader delivers. */
    private static byte[] loadReader() {
        try (var in = HostTransferCard.class.getResourceAsStream(CPM_ADAPTER)) {
            if (in == null) {
                throw new IllegalStateException("hostfiles: CP/M adapter resource " + CPM_ADAPTER + " is missing");
            }
            byte[] hex = in.readAllBytes();
            byte[] stream = java.util.Arrays.copyOf(hex, hex.length + 1);
            stream[hex.length] = CPM_END_OF_FILE;
            return stream;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("hostfiles: can't read the CP/M adapter", e);
        }
    }

    private static byte[] loadFirmware() {
        try (var in = HostTransferCard.class.getResourceAsStream(FIRMWARE)) {
            if (in == null) {
                throw new IllegalStateException("hostfiles: firmware resource " + FIRMWARE + " is missing");
            }
            byte[] image = in.readAllBytes();
            if (image.length < SLOT_PAGE + BANK_SIZE || (image.length - SLOT_PAGE) % BANK_SIZE != 0) {
                throw new IllegalStateException("hostfiles: firmware image has an unexpected size: " + image.length);
            }
            return image;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("hostfiles: can't read the firmware", e);
        }
    }

    @Override
    public int readIoSwitch(int offset) {
        if (offset == Protocol.REG_SERIAL_STATUS) {
            return Protocol.SERIAL_RECEIVE_FULL | Protocol.SERIAL_TRANSMIT_EMPTY; // always a byte to read, always room
        }
        if (offset == Protocol.REG_SERIAL_DATA) {
            int value = reader[readerPos] & 0xFF;
            readerPos = (readerPos + 1) % reader.length; // after the ^Z, the adapter again
            return value;
        }
        noteActivity();
        switch (offset) {
            case Protocol.REG_REQUEST:
                return nextRequest();
            case Protocol.REG_DATA:
                return toAdapterPos < toAdapter.length ? toAdapter[toAdapterPos++] & 0xFF : 0;
            case Protocol.REG_STATUS:
                return Protocol.VERSION | (host != null ? Protocol.STATUS_HOST_AVAILABLE : 0);
            case Protocol.REG_TYPING:
                return typing;
            case Protocol.REG_PHASE:
                return phase;
            default:
                return 0;
        }
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        if (offset == Protocol.REG_SERIAL_STATUS) {
            if ((value & Protocol.SERIAL_MASTER_RESET) == Protocol.SERIAL_MASTER_RESET) {
                readerPos = 0; // CP/M's server master-resets the 6850 at every cold boot
                servedByCpm = true;
            }
            return;
        }
        if (offset == Protocol.REG_SERIAL_DATA) {
            return; // CP/M's punch device: accepted and discarded
        }
        noteActivity();
        switch (offset) {
            case Protocol.REG_DATA -> fromAdapter.write(value);
            case Protocol.REG_COMPLETE -> complete(value & 0xFF);
            case Protocol.REG_STATUS -> romBank = value & 0xFF;
            case Protocol.REG_PRINT -> printSpool.append(value & 0xFF);
            case Protocol.REG_TYPING -> typing = value & 0xFF;
            case Protocol.REG_PHASE -> phase = value & 0xFF;
            case Protocol.REG_TRIM -> printSpool.trimListing();
            default -> {
                // unused register
            }
        }
    }

    /** RESET ends any session at once (TRANSFER-CARD.md 5.1). */
    @Override
    public void onReset() {
        endSession(TransferHost.EndReason.RESET, "RESET");
        fromAdapter.reset();
        toAdapter = new byte[0];
        toAdapterPos = 0;
        romBank = 0;
        typing = 0; // a capture interrupted by RESET is abandoned
        phase = 0;
        servedByCpm = false;
    }

    /**
     * Whether CP/M is running: its 6502 server initializes every serial
     * card's 6850 when CP/M boots, and nothing else this card serves does.
     * Cleared by RESET, after which CP/M, if it boots again, sets it again.
     * Safe from any thread -- File > Start CP/M Transfer is enabled by it.
     *
     * @return true if CP/M has initialized this card since the last RESET
     */
    public boolean servedByCpm() {
        return servedByCpm;
    }

    /**
     * Any register access shows the adapter is alive -- including the data
     * bytes of a long reply, during which it neither polls nor completes.
     */
    private void noteActivity() {
        if (session != null) {
            session.noteAdapterActivity();
        }
    }

    private int nextRequest() {
        if (session == null) {
            return Protocol.REQ_END; // nothing to serve: the adapter should finish
        }
        if (session.awaitingCompletion()) {
            return 0;
        }
        TransferSession.Request<?> request = session.nextRequest();
        if (request == null) {
            return 0;
        }
        toAdapter = request.args;
        toAdapterPos = 0;
        return request.opcode;
    }

    private void complete(int code) {
        byte[] message = fromAdapter.toByteArray();
        fromAdapter.reset();
        switch (code) {
            case Protocol.MSG_BEGIN -> begin(message);
            case Protocol.MSG_STASH -> stash = message;
            case Protocol.MSG_RECALL -> {
                toAdapter = stash;
                toAdapterPos = 0;
            }
            default -> {
                if (code < 0x80 && session != null) {
                    if (session.complete(code, message)) {
                        endSession(TransferHost.EndReason.COMPLETED, "the session ended");
                    }
                }
            }
        }
    }

    private void begin(byte[] message) {
        endSession(TransferHost.EndReason.SUPERSEDED, "a new session began");
        if (host == null || message.length == 0) {
            return; // refused: the request register will answer END
        }
        Capabilities caps;
        try {
            caps = Capabilities.parse(java.util.Arrays.copyOfRange(message, 1, message.length));
        } catch (IllegalArgumentException e) {
            System.err.println("Host transfer: malformed capability record from the adapter: " + e.getMessage());
            return;
        }
        session = new TransferSession(caps, message[0] & 0xFF);
        host.sessionStarted(session);
    }

    private void endSession(TransferHost.EndReason reason, String why) {
        TransferSession ending = session;
        if (ending == null) {
            return;
        }
        session = null;
        ending.close(why);
        if (host != null) {
            host.sessionEnded(ending, reason);
        }
    }

    /** @return where characters printed to this card (PR#n) collect */
    public PrintSpool printSpool() {
        return printSpool;
    }

    /** Package-visible for tests: the ROM bank last selected. */
    int romBank() {
        return romBank;
    }
}
