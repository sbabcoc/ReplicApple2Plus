package com.nordstrom.emulator.transfer;

import com.nordstrom.emulator.system.SlotCard;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * version plus "host available" bit, write = ROM bank select.
 * <p>
 * Its firmware -- the guest OS adapter, built from {@code firmware/hostfiles}
 * with ca65 -- is a committed resource: 256 bytes for the card's own
 * {@code $Cn00} page, then 2K expansion ROM banks for {@code $C800-$CFFF},
 * selected through register {@code $3}.
 */
public final class HostTransferCard implements SlotCard {

    private static final String FIRMWARE = "hostfiles-firmware.rom";
    private static final int SLOT_PAGE = 0x100;
    private static final int BANK_SIZE = 0x800;

    private final byte[] firmware = loadFirmware();

    private volatile TransferHost host; // set on the UI thread, read on the emulation thread
    private Path initialDirectory;

    private TransferSession session;
    private final ByteArrayOutputStream fromAdapter = new ByteArrayOutputStream();
    private byte[] toAdapter = new byte[0];
    private int toAdapterPos;
    private byte[] stash = new byte[0];
    private int romBank;

    @Override
    public String getShortName() {
        return "hostfiles";
    }

    @Override
    public Set<String> getSupportedParameters() {
        return Set.of("dir");
    }

    /** {@code dir} (optional): the folder the host dialogs open in first; must exist. */
    @Override
    public void configure(Properties props) {
        String dir = props.getProperty("dir");
        if (dir != null) {
            Path path = Path.of(dir.trim());
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
        switch (offset) {
            case Protocol.REG_REQUEST:
                return nextRequest();
            case Protocol.REG_DATA:
                return toAdapterPos < toAdapter.length ? toAdapter[toAdapterPos++] & 0xFF : 0;
            case Protocol.REG_STATUS:
                return Protocol.VERSION | (host != null ? Protocol.STATUS_HOST_AVAILABLE : 0);
            default:
                return 0;
        }
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        switch (offset) {
            case Protocol.REG_DATA -> fromAdapter.write(value);
            case Protocol.REG_COMPLETE -> complete(value & 0xFF);
            case Protocol.REG_STATUS -> romBank = value & 0xFF;
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
    }

    private int nextRequest() {
        if (session == null) {
            return Protocol.REQ_END; // nothing to serve: the adapter should finish
        }
        session.noteAdapterActivity();
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
                    session.noteAdapterActivity();
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

    /** Package-visible for tests: the ROM bank last selected. */
    int romBank() {
        return romBank;
    }
}
