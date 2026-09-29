package com.nordstrom.emulator.input;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadPollerTest {

    private static final class RecordingSink implements InputSink {
        final List<String> events = new CopyOnWriteArrayList<>();
        @Override public void setPaddle(int channel, int position) { events.add("P" + channel + "=" + position); }
        @Override public void disconnectPaddle(int channel) { events.add("P" + channel + "=open"); }
        @Override public void setButton(int button, boolean pressed) { events.add("B" + button + "=" + pressed); }
    }

    private static final class ScriptedProvider implements PadProvider {
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final AtomicReference<PadSnapshot> current = new AtomicReference<>(PadSnapshot.NEUTRAL);
        final AtomicReference<String> description = new AtomicReference<>("test pad");
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger polls = new AtomicInteger();
        volatile RuntimeException failWith;

        @Override public PadSnapshot poll() {
            threads.add(Thread.currentThread());
            polls.incrementAndGet();
            if (failWith != null) {
                throw failWith;
            }
            return current.get();
        }
        @Override public String description() { threads.add(Thread.currentThread()); return description.get(); }
        @Override public void close() { threads.add(Thread.currentThread()); closed.set(true); }
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

    private static PrintStream capture(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true);
    }

    @Test
    void theProviderIsCreatedPolledAndClosedOnThePollersOwnThread() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        AtomicReference<Thread> creator = new AtomicReference<>();
        PadPoller poller = new PadPoller(() -> { creator.set(Thread.currentThread()); return provider; },
            new InputMapper(InputMapping.defaults(), new RecordingSink()), 1, capture(new ByteArrayOutputStream()));
        poller.start();
        assertTrue(waitFor(() -> provider.polls.get() >= 3, 3000));
        poller.stop(2000);

        assertTrue(provider.closed.get());
        assertEquals("input", creator.get().getName());
        assertTrue(creator.get() != Thread.currentThread(), "must not be created on the caller's thread");
        // SDL is tied to one thread, so create, every poll, description and close must all agree:
        for (Thread t : provider.threads) {
            assertEquals(creator.get(), t, "provider was touched from a different thread");
        }
    }

    @Test
    void snapshotsFromTheProviderReachTheSink() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        RecordingSink sink = new RecordingSink();
        PadPoller poller = new PadPoller(() -> provider,
            new InputMapper(InputMapping.defaults(), sink), 1, capture(new ByteArrayOutputStream()));
        poller.start();
        try {
            provider.current.set(PadSnapshot.builder().press(PadButton.A).build());
            assertTrue(waitFor(() -> sink.events.contains("B0=true"), 3000), "the press should arrive: " + sink.events);
        } finally {
            poller.stop(2000);
        }
    }

    @Test
    void stoppingReleasesEverythingTheProviderHadHeld() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        RecordingSink sink = new RecordingSink();
        PadPoller poller = new PadPoller(() -> provider,
            new InputMapper(InputMapping.defaults(), sink), 1, capture(new ByteArrayOutputStream()));
        poller.start();
        provider.current.set(PadSnapshot.builder().axis(PadAxis.LEFT_X, 1f).press(PadButton.A).build());
        assertTrue(waitFor(() -> sink.events.contains("B0=true") && sink.events.contains("P0=255"), 3000));

        poller.stop(2000);

        assertEquals("B0=false", lastEventFor(sink, "B0"), "no button may stay pressed after polling stops");
        assertEquals("P0=open", lastEventFor(sink, "P0"),
            "once polling stops the pad is gone: the paddle must read as unplugged, not stay deflected or centered");
    }

    private static String lastEventFor(RecordingSink sink, String prefix) {
        String last = null;
        for (String e : sink.events) {
            if (e.startsWith(prefix + "=")) {
                last = e;
            }
        }
        return last;
    }

    @Test
    void noProviderMeansTheThreadEndsQuietlyAndNothingIsSent() throws Exception {
        RecordingSink sink = new RecordingSink();
        PadPoller poller = new PadPoller(() -> null,
            new InputMapper(InputMapping.defaults(), sink), 1, capture(new ByteArrayOutputStream()));
        poller.start();
        poller.stop(2000);
        assertEquals(List.of(), sink.events);
    }

    @Test
    void aProviderThatFailsToStartIsReportedNotFatal() throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PadPoller poller = new PadPoller(() -> { throw new IllegalStateException("boom"); },
            new InputMapper(InputMapping.defaults(), new RecordingSink()), 1, capture(log));
        poller.start();
        poller.stop(2000);
        assertTrue(log.toString().contains("failed to start") && log.toString().contains("boom"), log.toString());
    }

    @Test
    void aPollFailureIsReportedNeutralsTheInputAndStillClosesTheProvider() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        RecordingSink sink = new RecordingSink();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PadPoller poller = new PadPoller(() -> provider,
            new InputMapper(InputMapping.defaults(), sink), 1, capture(log));
        poller.start();
        provider.current.set(PadSnapshot.builder().press(PadButton.A).build());
        assertTrue(waitFor(() -> sink.events.contains("B0=true"), 3000));

        provider.failWith = new IllegalStateException("device vanished");
        assertTrue(waitFor(provider.closed::get, 3000), "the provider must be closed after a failure");
        poller.stop(2000);

        assertTrue(log.toString().contains("device vanished"), log.toString());
        assertEquals("B0=false", lastEventFor(sink, "B0"), "a held button must be released, not stuck");
    }

    @Test
    void aChangeOfDescriptionIsLoggedOnceNotEveryPoll() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PadPoller poller = new PadPoller(() -> provider,
            new InputMapper(InputMapping.defaults(), new RecordingSink()), 1, capture(log));
        poller.start();
        assertTrue(waitFor(() -> provider.polls.get() >= 5, 3000));
        provider.description.set("another pad");
        int seen = provider.polls.get();
        assertTrue(waitFor(() -> provider.polls.get() >= seen + 5, 3000));
        poller.stop(2000);

        String text = log.toString();
        assertEquals(1, text.split("test pad", -1).length - 1, "logged once despite many polls: " + text);
        assertEquals(1, text.split("another pad", -1).length - 1, text);
    }

    @Test
    void aPollerCannotBeStartedTwice() {
        PadPoller poller = new PadPoller(() -> null,
            new InputMapper(InputMapping.defaults(), new RecordingSink()), 1, capture(new ByteArrayOutputStream()));
        poller.start();
        boolean threw = false;
        try {
            poller.start();
        } catch (IllegalStateException e) {
            threw = true;
        }
        poller.stop(1000);
        assertTrue(threw);
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("input") && t.isAlive()));
    }
}
