package com.nordstrom.emulator.transfer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Imports and exports single files through a {@link TransferSession}:
 * naming (TRANSFER-CARD.md 4.4), text conversion (4.5), and conflicts. Meant
 * for a worker thread -- never the emulation thread, which answers the
 * requests, nor a UI thread. It waits on each request in short slices, so
 * {@link #cancel} takes effect promptly even if the Apple stops answering.
 */
public final class TransferOperations {

    /** Asks the user what to do when a name is already taken. Called on the worker thread. */
    public interface Conflicts {
        /**
         * @param guestName the guest file that already exists
         * @return true to replace it
         */
        boolean replaceGuestFile(String guestName);

        /**
         * @param hostFile the host file that already exists
         * @return true to overwrite it
         */
        boolean overwriteHostFile(Path hostFile);
    }

    /** How one file's transfer ended. */
    public enum Outcome {
        /** Transferred. */
        DONE,
        /** Not transferred, by the user's choice. */
        SKIPPED,
        /** Not transferred: an error. */
        FAILED,
        /** Not transferred: cancelled, or the session ended. */
        CANCELLED
    }

    /**
     * @param outcome how it ended
     * @param name    the file it produced or concerned
     * @param detail  an explanation for the log, or empty
     */
    public record Result(Outcome outcome, String name, String detail) {
    }

    /** The wait was cancelled. */
    static final class Cancelled extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private final TransferSession session;
    private volatile boolean cancelled;

    /** @param session the session to work through */
    public TransferOperations(TransferSession session) {
        this.session = session;
    }

    /** Stops the current and any further operation at the next wait. */
    public void cancel() {
        cancelled = true;
    }

    /** @return true once {@link #cancel} has been called */
    public boolean isCancelled() {
        return cancelled;
    }

    private <T> T await(CompletableFuture<T> future) throws TransferException, Cancelled {
        while (true) {
            if (cancelled) {
                throw new Cancelled();
            }
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillWaiting) {
                // check for cancellation, then keep waiting
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TransferException te) {
                    throw te;
                }
                throw new TransferException(ResultCode.IO_ERROR, -1, String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
        }
    }

    /**
     * @return the guest's top-level containers
     * @throws TransferException if the request fails or the session ends
     */
    public List<String> volumes() throws TransferException {
        try {
            return await(session.volumes());
        } catch (Cancelled c) {
            return List.of();
        }
    }

    /**
     * @param container a volume or directory
     * @return its entries
     * @throws TransferException if the request fails or the session ends
     */
    public List<GuestEntry> list(List<String> container) throws TransferException {
        try {
            return await(session.list(container));
        } catch (Cancelled c) {
            return List.of();
        }
    }

    /**
     * Imports a host file into a guest directory.
     *
     * @param hostFile  the host file
     * @param directory the guest volume or directory to put it in
     * @param guestName the name to give it
     * @param conflicts asked if the name is taken
     * @return how it went
     */
    public Result importFile(Path hostFile, List<String> directory, String guestName, Conflicts conflicts) {
        Capabilities caps = session.capabilities();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(hostFile);
        } catch (IOException e) {
            return new Result(Outcome.FAILED, guestName, "can't read " + hostFile + ": " + e.getMessage());
        }
        HostNames.Decoded decoded = HostNames.decode(hostFile.getFileName().toString());
        boolean untyped = decoded.type() == null;
        boolean contentIsText = untyped && TextConversion.isPlainText(bytes);
        FileType type = decoded.typeOr(caps, contentIsText);
        int attributes = decoded.attributesOr(caps);
        boolean convert = caps.isConvertibleText(type) && (decoded.text() || contentIsText);
        byte[] data = convert ? TextConversion.toGuest(bytes, caps) : bytes;

        List<String> path = new ArrayList<>(directory);
        path.add(guestName);
        try {
            try {
                await(session.write(path, type, attributes, data));
            } catch (TransferException e) {
                if (e.result() != ResultCode.EXISTS) {
                    throw e;
                }
                if (!conflicts.replaceGuestFile(guestName)) {
                    return new Result(Outcome.SKIPPED, guestName, "already exists; kept the Apple's copy");
                }
                await(session.delete(path));
                await(session.write(path, type, attributes, data));
            }
            return new Result(Outcome.DONE, guestName, convert ? "text, converted" : "");
        } catch (TransferException e) {
            return failure(guestName, e);
        } catch (Cancelled c) {
            return new Result(Outcome.CANCELLED, guestName, "cancelled");
        }
    }

    /**
     * Checks that files can be written to a container before any are chosen
     * for it: its disk there, readable, and not write-protected.
     *
     * @param container the destination volume, directory or drive
     * @return {@link ResultCode#OK} if they can, else why not -- {@code null}
     *     if the check was cancelled
     */
    public ResultCode checkWritable(List<String> container) {
        try {
            await(session.checkWritable(container));
            return ResultCode.OK;
        } catch (TransferException e) {
            return e.result() != null ? e.result() : ResultCode.IO_ERROR;
        } catch (Cancelled c) {
            return null;
        }
    }

    /**
     * A guest file read for export: the name the naming convention gives it
     * on the host, and its bytes as they'll be saved there -- or, if the read
     * didn't succeed, why not.
     *
     * @param hostName  the host name (section 4 of TRANSFER-CARD.md), or null on failure
     * @param data      the bytes to save, text already converted, or null on failure
     * @param converted true if the data is text converted for the host
     * @param failure   null if the read succeeded; otherwise its result
     */
    public record ExportRead(String hostName, byte[] data, boolean converted, Result failure) {

        /** @return true if the file was read and is ready to save */
        public boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * Reads a guest file for export. Reading comes first because the host
     * name may depend on it: a listing may not have read far enough to know
     * the aux value (a DOS 3.3 B file's load address), and READ supplies it.
     *
     * @param file  the guest file
     * @param entry its listing entry (for its type and attributes)
     * @return the host name and bytes, or why the read failed
     */
    public ExportRead readForExport(List<String> file, GuestEntry entry) {
        Capabilities caps = session.capabilities();
        GuestData read;
        try {
            read = await(session.read(file));
        } catch (TransferException e) {
            return new ExportRead(null, null, false, failure(entry.name(), e));
        } catch (Cancelled c) {
            return new ExportRead(null, null, false, new Result(Outcome.CANCELLED, entry.name(), "cancelled"));
        }
        FileType type = read.type() != null ? read.type() : entry.type();
        String hostName = HostNames.encode(entry.name(), type, entry.attributes(), caps);
        boolean convert = caps.isConvertibleText(type);
        byte[] data = convert ? TextConversion.toHost(read.bytes(), caps) : read.bytes();
        return new ExportRead(hostName, data, convert, null);
    }

    /**
     * Saves a file read for export, under whatever name and folder the user
     * chose -- replacing an existing file there, which the caller has
     * confirmed.
     *
     * @param read   a successful {@link #readForExport} result
     * @param target the host file to save it as
     * @return how it went
     */
    public Result saveExport(ExportRead read, Path target) {
        String name = target.getFileName().toString();
        try {
            writeAtomically(target, read.data());
        } catch (IOException e) {
            return new Result(Outcome.FAILED, name, "can't write " + target + ": " + e.getMessage());
        }
        return new Result(Outcome.DONE, name, read.converted() ? "text, converted" : "");
    }

    /**
     * Exports a guest file to a host folder, under the name the naming
     * convention gives it.
     *
     * @param file      the guest file
     * @param entry     its listing entry (for its type and attributes)
     * @param folder    the host folder
     * @param conflicts asked if the host name is taken
     * @return how it went
     */
    public Result exportFile(List<String> file, GuestEntry entry, Path folder, Conflicts conflicts) {
        ExportRead read = readForExport(file, entry);
        if (!read.succeeded()) {
            return read.failure();
        }
        Path target = folder.resolve(read.hostName());
        if (Files.exists(target) && !conflicts.overwriteHostFile(target)) {
            return new Result(Outcome.SKIPPED, read.hostName(), "already exists; kept the host's copy");
        }
        return saveExport(read, target);
    }

    /** Asks the adapter to finish; the session ends when it answers. */
    public void end() {
        session.end();
    }

    private static Result failure(String name, TransferException e) {
        if (e instanceof TransferException.Abandoned) {
            return new Result(Outcome.CANCELLED, name, "the session ended (" + e.getMessage() + ")");
        }
        String detail = e.result() == ResultCode.OTHER && e.nativeCode() >= 0
            ? String.format("%s (error $%02X)", e.getMessage(), e.nativeCode())
            : e.getMessage();
        return new Result(Outcome.FAILED, name, detail);
    }

    /** Writes via a temporary file in the same folder, so a failure never leaves half a file. */
    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".transfer", ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
