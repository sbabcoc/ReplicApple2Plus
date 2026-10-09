package com.nordstrom.emulator.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * One transfer session between the host and a guest adapter, from BEGIN to
 * END or abandonment. The host makes requests from any thread; each returns a
 * future the card completes, on the emulation thread, when the adapter
 * answers. Futures of requests that can't complete -- RESET, a new session,
 * END -- complete exceptionally with {@link TransferException.Abandoned}.
 * <p>
 * Requests are answered one at a time, in order.
 */
public final class TransferSession {

    private final Capabilities capabilities;
    private final int adapterVersion;
    private final ConcurrentLinkedQueue<Request<?>> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean open = true;
    private volatile long lastAdapterActivity = System.nanoTime();
    private Request<?> current; // emulation thread only

    TransferSession(Capabilities capabilities, int adapterVersion) {
        this.capabilities = capabilities;
        this.adapterVersion = adapterVersion;
    }

    /** @return what the adapter declared about its OS */
    public Capabilities capabilities() {
        return capabilities;
    }

    /** @return the protocol version the adapter speaks */
    public int adapterVersion() {
        return adapterVersion;
    }

    /** @return false once the session has ended */
    public boolean isOpen() {
        return open;
    }

    /**
     * @return nanoseconds since the adapter last touched the card -- for the
     *         host's "the Apple stopped responding" choice (TRANSFER-CARD.md 5.1)
     */
    public long nanosSinceAdapterActivity() {
        return System.nanoTime() - lastAdapterActivity;
    }

    /** @return the top-level containers: volumes, or drives */
    public CompletableFuture<List<String>> volumes() {
        return submit(Protocol.REQ_VOLUMES, new byte[0], TransferSession::names);
    }

    /**
     * @param container a volume or directory, as name components
     * @return its entries
     */
    public CompletableFuture<List<GuestEntry>> list(List<String> container) {
        return submit(Protocol.REQ_LIST, new Wire.Writer().path(container).toByteArray(), TransferSession::entries);
    }

    /**
     * @param file a file, as name components
     * @return its bytes, exactly as the guest stores them
     */
    public CompletableFuture<byte[]> read(List<String> file) {
        return submit(Protocol.REQ_READ, new Wire.Writer().path(file).toByteArray(), reply -> reply);
    }

    /**
     * Creates a file and writes it.
     *
     * @param file       a file, as name components
     * @param type       its type
     * @param attributes its attribute byte
     * @param data       its bytes, exactly as the guest should store them
     * @return completion
     */
    public CompletableFuture<Void> write(List<String> file, FileType type, int attributes, byte[] data) {
        byte[] args = new Wire.Writer().path(file).string(type.tag()).u16(type.aux()).u8(attributes)
            .u32(data.length).bytes(data).toByteArray();
        return submit(Protocol.REQ_WRITE, args, reply -> null);
    }

    /**
     * @param directory a directory to create, as name components
     * @return completion
     */
    public CompletableFuture<Void> makeDirectory(List<String> directory) {
        return submit(Protocol.REQ_MAKE_DIR, new Wire.Writer().path(directory).toByteArray(), reply -> null);
    }

    /**
     * @param file a file to delete, as name components
     * @return completion
     */
    public CompletableFuture<Void> delete(List<String> file) {
        return submit(Protocol.REQ_DELETE, new Wire.Writer().path(file).toByteArray(), reply -> null);
    }

    /**
     * Asks the adapter to restore borrowed memory and return to BASIC, ending the session.
     *
     * @return completion
     */
    public CompletableFuture<Void> end() {
        return submit(Protocol.REQ_END, new byte[0], reply -> null);
    }

    private <T> CompletableFuture<T> submit(int opcode, byte[] args, Function<byte[], T> parser) {
        Request<T> request = new Request<>(opcode, args, parser);
        if (!open) {
            request.future.completeExceptionally(new TransferException.Abandoned("the session has ended"));
            return request.future;
        }
        queue.add(request);
        if (!open) { // ended while we were adding: make sure it doesn't wait forever
            abandonQueued("the session has ended");
        }
        return request.future;
    }

    private static List<String> names(byte[] reply) {
        Wire.Reader r = new Wire.Reader(reply);
        List<String> out = new ArrayList<>();
        for (String name = r.string(); !name.isEmpty(); name = r.string()) {
            out.add(name);
        }
        return out;
    }

    private static List<GuestEntry> entries(byte[] reply) {
        Wire.Reader r = new Wire.Reader(reply);
        List<GuestEntry> out = new ArrayList<>();
        for (String name = r.string(); !name.isEmpty(); name = r.string()) {
            boolean directory = r.u8() != 0;
            String tag = r.string();
            int aux = r.u16();
            int attributes = r.u8();
            long size = r.u32();
            out.add(new GuestEntry(name, directory, new FileType(tag, aux), attributes, size));
        }
        return out;
    }

    // ---- card side: emulation thread only ----

    void noteAdapterActivity() {
        lastAdapterActivity = System.nanoTime();
    }

    /** @return true if a request has been handed to the adapter and not yet completed */
    boolean awaitingCompletion() {
        return current != null;
    }

    /**
     * Hands the next request to the adapter, if there is one.
     *
     * @return the request's opcode and arguments, or null if none is waiting
     */
    Request<?> nextRequest() {
        if (current != null || !open) {
            return null;
        }
        current = queue.poll();
        return current;
    }

    /**
     * Completes the current request.
     *
     * @param code    the completion code, $00-$7F
     * @param payload the adapter's reply
     * @return true if that request was END, which ends the session
     */
    boolean complete(int code, byte[] payload) {
        Request<?> request = current;
        if (request == null) {
            return false; // a completion with nothing outstanding: ignored
        }
        current = null;
        ResultCode result = ResultCode.of(code);
        if (result == ResultCode.OK) {
            request.succeed(payload);
        } else {
            int nativeCode = -1;
            String message = result.name();
            if (result == ResultCode.OTHER && payload.length > 0) {
                Wire.Reader r = new Wire.Reader(payload);
                nativeCode = r.u8();
                if (!r.atEnd()) {
                    message = r.string();
                }
            }
            request.future.completeExceptionally(new TransferException(result, nativeCode, message));
        }
        return request.opcode == Protocol.REQ_END && result == ResultCode.OK;
    }

    /** Ends the session, failing everything outstanding. */
    void close(String why) {
        open = false;
        if (current != null) {
            current.future.completeExceptionally(new TransferException.Abandoned(why));
            current = null;
        }
        abandonQueued(why);
    }

    private void abandonQueued(String why) {
        for (Request<?> r = queue.poll(); r != null; r = queue.poll()) {
            r.future.completeExceptionally(new TransferException.Abandoned(why));
        }
    }

    /** A request waiting for, or being handled by, the adapter. */
    static final class Request<T> {
        final int opcode;
        final byte[] args;
        private final Function<byte[], T> parser;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Request(int opcode, byte[] args, Function<byte[], T> parser) {
            this.opcode = opcode;
            this.args = args;
            this.parser = parser;
        }

        void succeed(byte[] payload) {
            try {
                future.complete(parser.apply(payload));
            } catch (RuntimeException e) {
                future.completeExceptionally(new TransferException(ResultCode.IO_ERROR, -1,
                    "malformed reply from the adapter: " + e.getMessage()));
            }
        }
    }
}
