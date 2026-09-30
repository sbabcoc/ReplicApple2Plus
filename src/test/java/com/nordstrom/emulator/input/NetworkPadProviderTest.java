package com.nordstrom.emulator.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link NetworkPadProvider} against a real socket, over real
 * loopback, with a real second {@link DatagramSocket} standing in for
 * the Android sender -- not a mock of the parsing logic, but the actual
 * wire protocol crossing an actual UDP socket, the same way the real
 * companion app will reach it.
 */
class NetworkPadProviderTest {

    private DatagramSocket sender;
    private PadProvider provider;
    private ByteArrayOutputStream log;

    private void startProviderOn(int port) throws Exception {
        log = new ByteArrayOutputStream();
        provider = NetworkPadProvider.create(port, new PrintStream(log, true));
        assertNotNull(provider, "provider should start; log said: " + log);
        sender = new DatagramSocket();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
        if (sender != null) {
            sender.close();
        }
    }

    private void send(int port, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        sender.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(2);
        }
        return condition.getAsBoolean();
    }

    private PadSnapshot pollUntilPresent(long timeoutMillis) throws InterruptedException {
        PadSnapshot[] result = {PadSnapshot.ABSENT};
        boolean ok = waitFor(() -> {
            result[0] = provider.poll();
            return result[0].isPresent();
        }, timeoutMillis);
        assertTrue(ok, "expected a present snapshot within " + timeoutMillis + "ms");
        return result[0];
    }

    @Test
    void aFullSnapshotPacketParsesCorrectly() throws Exception {
        int port = 41001;
        startProviderOn(port);
        send(port, "LEFT_X=-0.23 LEFT_Y=0.87 RIGHT_X=0.00 RIGHT_Y=1.00 BUTTONS=A,DPAD_UP");

        PadSnapshot s = pollUntilPresent(2000);
        assertEquals(-0.23f, s.axis(PadAxis.LEFT_X));
        assertEquals(0.87f, s.axis(PadAxis.LEFT_Y));
        assertEquals(0.00f, s.axis(PadAxis.RIGHT_X));
        assertEquals(1.00f, s.axis(PadAxis.RIGHT_Y));
        assertTrue(s.pressed(PadButton.A));
        assertTrue(s.pressed(PadButton.DPAD_UP));
        assertFalse(s.pressed(PadButton.B));
    }

    @Test
    void anAxisNotMentionedDefaultsToZero() throws Exception {
        int port = 41002;
        startProviderOn(port);
        send(port, "LEFT_X=1.00");

        PadSnapshot s = pollUntilPresent(2000);
        assertEquals(1.00f, s.axis(PadAxis.LEFT_X));
        assertEquals(0f, s.axis(PadAxis.LEFT_Y));
        assertEquals(0f, s.axis(PadAxis.RIGHT_X));
        assertEquals(0f, s.axis(PadAxis.RIGHT_Y));
    }

    @Test
    void emptyButtonsFieldMeansExplicitlyNothingPressed() throws Exception {
        int port = 41003;
        startProviderOn(port);
        send(port, "LEFT_X=0.50 BUTTONS=");

        PadSnapshot s = pollUntilPresent(2000);
        assertEquals(0.50f, s.axis(PadAxis.LEFT_X));
        for (PadButton b : PadButton.values()) {
            assertFalse(s.pressed(b), b + " should not be pressed");
        }
    }

    @Test
    void theLiteralAbsentBodyReportsNoPad() throws Exception {
        int port = 41004;
        startProviderOn(port);
        send(port, "LEFT_X=1.00");
        pollUntilPresent(2000);

        send(port, "ABSENT");
        boolean becameAbsent = waitFor(() -> !provider.poll().isPresent(), 2000);
        assertTrue(becameAbsent, "expected ABSENT after the literal ABSENT packet");
    }

    @Test
    void aBadFieldIsSkippedButGoodFieldsInTheSamePacketStillApply() throws Exception {
        int port = 41005;
        startProviderOn(port);
        send(port, "LEFT_X=0.75 NOT_AN_AXIS=3 BUTTONS=A,NOT_A_BUTTON,B");

        PadSnapshot s = pollUntilPresent(2000);
        assertEquals(0.75f, s.axis(PadAxis.LEFT_X));
        assertTrue(s.pressed(PadButton.A));
        assertTrue(s.pressed(PadButton.B));
        assertTrue(log.toString().contains("NOT_AN_AXIS"), "the bad field should be logged: " + log);
    }

    @Test
    void aPacketWithNothingUsableIsDiscardedKeepingTheLastGoodState() throws Exception {
        int port = 41006;
        startProviderOn(port);
        send(port, "LEFT_X=0.42");
        PadSnapshot good = pollUntilPresent(2000);
        assertEquals(0.42f, good.axis(PadAxis.LEFT_X));

        send(port, "garbage with no equals signs at all");
        Thread.sleep(100);
        PadSnapshot stillGood = provider.poll();
        assertEquals(0.42f, stillGood.axis(PadAxis.LEFT_X), "a wholly unusable packet must not corrupt the prior state");
    }

    @Test
    void onlyTheMostRecentOfSeveralQueuedPacketsIsKept() throws Exception {
        int port = 41007;
        startProviderOn(port);
        for (int i = 0; i <= 10; i++) {
            send(port, "LEFT_X=" + (i / 10.0));
        }
        Thread.sleep(50); // let all ten arrive in the OS socket buffer before polling
        PadSnapshot s = pollUntilPresent(2000);
        assertEquals(1.00f, s.axis(PadAxis.LEFT_X), "should reflect the last packet sent, not an earlier one");
    }

    @Test
    void goingQuietForOverASecondReportsAbsent() throws Exception {
        int port = 41008;
        startProviderOn(port);
        send(port, "LEFT_X=1.00");
        pollUntilPresent(2000);

        boolean becameAbsent = waitFor(() -> !provider.poll().isPresent(), 2500);
        assertTrue(becameAbsent, "should go ABSENT after no packets for over a second");
    }

    @Test
    void neverConnectingAtAllReadsAsAbsentFromTheStart() throws Exception {
        int port = 41009;
        startProviderOn(port);
        assertEquals(PadSnapshot.ABSENT, provider.poll());
    }

    @Test
    void aSecondProviderCannotBindTheSamePortAndReturnsNullInsteadOfThrowing() throws Exception {
        int port = 41010;
        startProviderOn(port);
        ByteArrayOutputStream secondLog = new ByteArrayOutputStream();
        PadProvider second = NetworkPadProvider.create(port, new PrintStream(secondLog, true));
        assertNull(second, "a second provider on the same port should fail to start, not throw");
        assertFalse(secondLog.toString().isEmpty(), "the failure should be logged");
    }

    @Test
    void closeReleasesThePortForReuse() throws Exception {
        int port = 41011;
        startProviderOn(port);
        provider.close();
        provider = null; // tearDown must not double-close

        PadProvider reopened = NetworkPadProvider.create(port, new PrintStream(new ByteArrayOutputStream(), true));
        assertNotNull(reopened, "the port should be free again after close()");
        reopened.close();
    }

    @Test
    void descriptionReflectsConnectionState() throws Exception {
        int port = 41012;
        startProviderOn(port);
        assertTrue(provider.description().contains("waiting"), provider.description());

        send(port, "LEFT_X=1.00");
        pollUntilPresent(2000);
        assertTrue(provider.description().toLowerCase().contains("connected"), provider.description());
    }
}
