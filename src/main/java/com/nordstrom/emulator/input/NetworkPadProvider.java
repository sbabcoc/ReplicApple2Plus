package com.nordstrom.emulator.input;

import java.io.IOException;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reads a gamepad over the local network instead of through a native
 * library -- the route needed on Android/Termux/PRoot, where an
 * unprivileged process has no access to any device node at all (an
 * {@code ls -l /dev} inside that environment shows nothing, ruling out
 * every device-node-based approach, virtual or real; SDL and Jamepad
 * need one either way). A companion Android app reads the real,
 * already-paired controller through Android's own, unprivileged game
 * controller APIs and sends its state here as plain text UDP packets on
 * localhost -- the same loopback path already proven to cross this
 * exact Android/Termux/PRoot boundary by the PulseAudio bridge set up
 * earlier for audio.
 * <p>
 * <b>Wire format.</b> One UDP packet per update, ASCII text, no
 * multi-packet reassembly or fragmentation handling -- a snapshot is
 * small and this is loopback-only, so a single datagram is never split.
 * Two forms:
 * <ul>
 *   <li>the literal body {@code ABSENT} -- no controller connected on
 *       the Android side right now</li>
 *   <li>space-separated {@code KEY=VALUE} pairs, in any order:
 *       <ul>
 *         <li>an axis name from {@link PadAxis} (e.g. {@code LEFT_X}) mapped to
 *             a float from -1.0 to 1.0; an axis not mentioned is 0.0</li>
 *         <li>{@code BUTTONS} mapped to a comma-separated list of
 *             {@link PadButton} names (e.g. {@code BUTTONS=A,DPAD_UP});
 *             omit the whole field for "nothing pressed"</li>
 *       </ul>
 *       For example: {@code LEFT_X=-0.23 LEFT_Y=0.87 BUTTONS=A,X}</li>
 * </ul>
 * This deliberately mirrors this project's own enum names rather than
 * inventing a separate vocabulary, so the same names appear in the
 * Android sender, this parser, and everything downstream.
 * <p>
 * <b>Malformed input never stops this provider.</b> An unrecognized key,
 * an unparsable float, or any other single bad field is skipped (with a
 * throttled log message) and the rest of that same packet is still
 * applied; a packet that fails outright is discarded. This differs
 * deliberately from {@link InputMapping}'s strict, fail-fast validation
 * of a one-time config file: {@link PadPoller} treats an exception from
 * {@link #poll} as fatal to the whole session (see its own Javadoc), and
 * a single corrupted or slightly-ahead-of-protocol packet from a
 * long-running remote process is exactly the kind of thing that must
 * not be allowed to permanently kill controller input for the rest of a
 * session.
 * <p>
 * <b>Staleness.</b> If no packet arrives for {@link #STALE_AFTER_MILLIS},
 * this reports {@link PadSnapshot#ABSENT} -- the Android app being
 * closed, its network dropping, or the controller itself disconnecting
 * on that end all look the same from here, and all three should
 * release the paddles exactly as if the pad had been unplugged.
 */
public final class NetworkPadProvider implements PadProvider {

    /** The default port, used unless the caller picks another. */
    public static final int DEFAULT_PORT = 8942;

    private static final long STALE_AFTER_MILLIS = 1000;
    private static final int RECEIVE_TIMEOUT_MILLIS = 2; // just long enough to notice an empty socket buffer
    private static final int MAX_PACKET_BYTES = 512; // generous; a real snapshot line is under 120 bytes
    private static final long LOG_THROTTLE_NANOS = 1_000_000_000L;

    private final DatagramSocket socket;
    private final PrintStream log;
    private final byte[] receiveBuffer = new byte[MAX_PACKET_BYTES];

    private PadSnapshot last = PadSnapshot.ABSENT;
    private long lastPacketAtMillis = -1;
    private String description = "waiting for a network gamepad on port ";
    // Seeded from a real clock reading already more than the throttle
    // window in the past, so the very first warning always clears the
    // threshold below. Long.MIN_VALUE was tried first and is wrong: with
    // a subtraction-based comparison (now - last > threshold), computing
    // now - Long.MIN_VALUE overflows a signed long and wraps to a large
    // NEGATIVE number instead of a large positive one, so the first
    // warning after construction never actually logged. Confirmed
    // directly -- a real packet with a bad field produced no log line at
    // all until this was fixed.
    private long lastWarningLoggedNanos = System.nanoTime() - LOG_THROTTLE_NANOS - 1;

    private NetworkPadProvider(DatagramSocket socket, PrintStream log, int port) {
        this.socket = socket;
        this.log = log;
        this.description = "waiting for a network gamepad on port " + port;
    }

    /**
     * Opens the listening socket. Call this on the thread that will
     * poll, matching every other {@link PadProvider}'s contract, even
     * though nothing about UDP itself requires it.
     *
     * @param port the UDP port to listen on, typically {@link #DEFAULT_PORT}
     * @param log where to report why startup failed, if it does
     * @return a working provider, or null if the port could not be bound (the reason is logged)
     */
    public static PadProvider create(int port, PrintStream log) {
        try {
            DatagramSocket socket = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
            return new NetworkPadProvider(socket, log, port);
        } catch (IOException e) {
            log.println("input: network gamepad support failed to start on port " + port + ": " + e);
            return null;
        }
    }

    @Override
    public PadSnapshot poll() {
        boolean receivedAny = drainAvailablePackets();
        if (receivedAny) {
            return last;
        }
        if (lastPacketAtMillis < 0 || System.currentTimeMillis() - lastPacketAtMillis > STALE_AFTER_MILLIS) {
            if (last.isPresent()) {
                description = "network gamepad went quiet (no packet for over " + STALE_AFTER_MILLIS + "ms)";
            }
            last = PadSnapshot.ABSENT;
        }
        return last;
    }

    /**
     * Reads every packet currently waiting, keeping only the most recent
     * one that parses -- older queued packets are stale by the time
     * they'd be applied, so there is nothing to gain from processing them.
     *
     * @return true if at least one packet was read (whether or not it parsed)
     */
    private boolean drainAvailablePackets() {
        boolean receivedAny = false;
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        while (true) {
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                break; // the socket buffer is empty right now
            } catch (IOException e) {
                warn("network gamepad read failed: " + e);
                break;
            }
            receivedAny = true;
            lastPacketAtMillis = System.currentTimeMillis();
            String body = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
            PadSnapshot parsed = parse(body);
            if (parsed != null) {
                if (!last.isPresent() && parsed.isPresent()) {
                    description = "network gamepad connected";
                }
                last = parsed;
            }
        }
        return receivedAny;
    }

    /**
     * Parses one packet body. Returns null (skipping this packet
     * entirely, keeping whatever the last good snapshot was) only when
     * nothing in it was usable at all; a packet with some good fields
     * and one bad one still applies the good ones.
     */
    private PadSnapshot parse(String body) {
        String trimmed = body.strip();
        if (trimmed.equals("ABSENT")) {
            return PadSnapshot.ABSENT;
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        PadSnapshot.Builder builder = PadSnapshot.builder();
        boolean anyFieldUnderstood = false;
        for (String field : trimmed.split("\\s+")) {
            int eq = field.indexOf('=');
            if (eq < 0) {
                warn("network gamepad: ignoring malformed field (no '='): \"" + field + "\"");
                continue;
            }
            String key = field.substring(0, eq);
            String value = field.substring(eq + 1);
            if (key.equals("BUTTONS")) {
                if (applyButtons(builder, value)) {
                    anyFieldUnderstood = true;
                }
                continue;
            }
            if (applyAxis(builder, key, value)) {
                anyFieldUnderstood = true;
            }
        }
        return anyFieldUnderstood ? builder.build() : null;
    }

    private boolean applyAxis(PadSnapshot.Builder builder, String key, String value) {
        PadAxis axis;
        try {
            axis = PadAxis.valueOf(key);
        } catch (IllegalArgumentException e) {
            warn("network gamepad: unknown axis name \"" + key + "\", ignoring that field");
            return false;
        }
        try {
            builder.axis(axis, Float.parseFloat(value));
            return true;
        } catch (NumberFormatException e) {
            warn("network gamepad: \"" + value + "\" is not a number for axis " + key + ", ignoring that field");
            return false;
        }
    }

    private boolean applyButtons(PadSnapshot.Builder builder, String value) {
        if (value.isEmpty()) {
            return true; // BUTTONS= with nothing after it: explicitly "none pressed", still a valid field
        }
        Set<PadButton> pressed = EnumSet.noneOf(PadButton.class);
        boolean anyValid = false;
        for (String name : value.split(",")) {
            try {
                pressed.add(PadButton.valueOf(name));
                anyValid = true;
            } catch (IllegalArgumentException e) {
                warn("network gamepad: unknown button name \"" + name + "\", ignoring it");
            }
        }
        builder.press(pressed.toArray(new PadButton[0]));
        return anyValid || value.isEmpty();
    }

    private void warn(String message) {
        long now = System.nanoTime();
        if (now - lastWarningLoggedNanos > LOG_THROTTLE_NANOS) {
            lastWarningLoggedNanos = now;
            log.println("input: " + message);
        }
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public void close() {
        socket.close();
    }
}
