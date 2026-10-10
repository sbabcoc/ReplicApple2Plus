package com.nordstrom.emulator.transfer;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The card's registers and session handling, driven the way the firmware will drive them. */
class HostTransferCardTest {

    /** A host that records what the card tells it. */
    static final class RecordingHost implements TransferHost {
        final List<TransferSession> started = new ArrayList<>();
        final List<EndReason> ended = new ArrayList<>();

        @Override
        public void sessionStarted(TransferSession session) {
            started.add(session);
        }

        @Override
        public void sessionEnded(TransferSession session, EndReason reason) {
            ended.add(reason);
        }
    }

    private HostTransferCard card;
    private RecordingHost host;
    private FakeAdapter adapter;

    @BeforeEach
    void setUp() {
        card = new HostTransferCard();
        host = new RecordingHost();
        card.setHost(host);
        adapter = new FakeAdapter(card);
    }

    private TransferSession begin() {
        adapter.begin(FakeAdapter.proDosLike());
        assertEquals(1, host.started.size());
        return host.started.get(0);
    }

    private static <T> T done(CompletableFuture<T> future) throws Exception {
        assertTrue(future.isDone(), "the request should have completed");
        return future.get();
    }

    private static TransferException failure(CompletableFuture<?> future) {
        assertTrue(future.isDone(), "the request should have completed"); // never block on get()
        ExecutionException e = assertThrows(ExecutionException.class, future::get);
        return assertInstanceOf(TransferException.class, e.getCause());
    }

    @Test
    void theStatusRegisterGivesTheVersionAndWhetherAHostIsThere() {
        assertEquals(Protocol.VERSION | Protocol.STATUS_HOST_AVAILABLE, card.readIoSwitch(Protocol.REG_STATUS));
        card.setHost(null);
        assertEquals(Protocol.VERSION, card.readIoSwitch(Protocol.REG_STATUS));
    }

    @Test
    void withNoSessionTheRequestRegisterSaysEnd() {
        assertEquals(Protocol.REQ_END, card.readIoSwitch(Protocol.REG_REQUEST));
    }

    @Test
    void beginStartsASessionWithTheAdaptersCapabilities() {
        TransferSession session = begin();
        assertTrue(session.isOpen());
        assertEquals(1, session.adapterVersion());
        assertEquals("PRODOS", session.capabilities().osName());
        assertTrue(session.capabilities().hierarchical());
        assertEquals(0, card.readIoSwitch(Protocol.REG_REQUEST), "nothing requested yet: poll again");
    }

    @Test
    void withNoHostBeginIsRefusedAndTheAdapterIsToldToEnd() {
        card.setHost(null);
        adapter.begin(FakeAdapter.proDosLike());
        assertEquals(Protocol.REQ_END, card.readIoSwitch(Protocol.REG_REQUEST));
    }

    @Test
    void aMalformedCapabilityRecordIsRefused() {
        adapter.begin(new byte[] {Protocol.CAP_OS_NAME, 40, 'X'}); // claims 40 bytes, has 1
        assertTrue(host.started.isEmpty());
        assertEquals(Protocol.REQ_END, card.readIoSwitch(Protocol.REG_REQUEST));
    }

    @Test
    void volumesAndListing() throws Exception {
        TransferSession session = begin();
        adapter.directories.add(List.of("VOL", "SUB"));
        adapter.files.put(List.of("VOL", "GAME"), new FakeAdapter.GuestFile("BIN", 0x2000, 0xE3, new byte[300]));

        CompletableFuture<List<String>> volumes = session.volumes();
        assertEquals(Protocol.REQ_VOLUMES, adapter.serveNext());
        assertEquals(List.of("VOL"), done(volumes));

        CompletableFuture<List<GuestEntry>> list = session.list(List.of("VOL"));
        adapter.serveNext();
        List<GuestEntry> entries = done(list);
        assertEquals(2, entries.size());
        assertEquals(new GuestEntry("SUB", true, new FileType("DIR", 0), 0xE3, 512), entries.get(0));
        assertEquals(new GuestEntry("GAME", false, new FileType("BIN", 0x2000), 0xE3, 300), entries.get(1));
    }

    @Test
    void writeThenReadRoundTripsALargeFileExactly() throws Exception {
        TransferSession session = begin();
        byte[] data = new byte[65_536];
        new Random(2026).nextBytes(data);

        CompletableFuture<Void> write = session.write(List.of("VOL", "BIG"), new FileType("BIN", 0x0800), 0xC3, data);
        assertEquals(Protocol.REQ_WRITE, adapter.serveNext());
        done(write);
        FakeAdapter.GuestFile stored = adapter.files.get(List.of("VOL", "BIG"));
        assertEquals("BIN", stored.tag());
        assertEquals(0x0800, stored.aux());
        assertEquals(0xC3, stored.attributes());
        assertArrayEquals(data, stored.data());

        CompletableFuture<GuestData> read = session.read(List.of("VOL", "BIG"));
        adapter.serveNext();
        assertArrayEquals(data, done(read).bytes());
        assertEquals(null, done(read).type(), "flag 0: no type sent, the listing's stands");
    }

    @Test
    void makeDirectoryAndDelete() throws Exception {
        TransferSession session = begin();
        adapter.files.put(List.of("VOL", "OLD"), new FakeAdapter.GuestFile("TXT", 0, 0xE3, new byte[1]));

        CompletableFuture<Void> mkdir = session.makeDirectory(List.of("VOL", "NEW"));
        adapter.serveNext();
        done(mkdir);
        assertTrue(adapter.directories.contains(List.of("VOL", "NEW")));

        CompletableFuture<Void> delete = session.delete(List.of("VOL", "OLD"));
        adapter.serveNext();
        done(delete);
        assertFalse(adapter.files.containsKey(List.of("VOL", "OLD")));
    }

    @Test
    void errorsComeBackAsResultsWithTheOsOwnCodeWhenGiven() {
        TransferSession session = begin();
        CompletableFuture<GuestData> missing = session.read(List.of("VOL", "NOPE"));
        adapter.serveNext();
        assertEquals(ResultCode.NOT_FOUND, failure(missing).result());

        CompletableFuture<Void> full = session.write(List.of("VOL", "FULL"), new FileType("BIN", 0), 0, new byte[4]);
        adapter.serveNext();
        TransferException e = failure(full);
        assertEquals(ResultCode.OTHER, e.result());
        assertEquals(0x48, e.nativeCode());
        assertEquals("DISK FULL", e.getMessage());
    }

    @Test
    void requestsAreServedOneAtATimeInOrder() throws Exception {
        TransferSession session = begin();
        CompletableFuture<List<String>> first = session.volumes();
        CompletableFuture<List<GuestEntry>> second = session.list(List.of("VOL"));
        assertEquals(Protocol.REQ_VOLUMES, card.readIoSwitch(Protocol.REG_REQUEST));
        assertEquals(0, card.readIoSwitch(Protocol.REG_REQUEST), "the first isn't complete: no second request yet");
        // answer the first by hand: one volume, then the empty string
        for (int b : new int[] {3, 'V', 'O', 'L', 0}) {
            card.writeIoSwitch(Protocol.REG_DATA, b);
        }
        adapter.finish(ResultCode.OK.code());
        assertEquals(List.of("VOL"), done(first));
        assertEquals(Protocol.REQ_LIST, adapter.serveNext());
        done(second);
    }

    @Test
    void endClosesTheSessionAndLaterRequestsAreAbandoned() throws Exception {
        TransferSession session = begin();
        CompletableFuture<Void> end = session.end();
        assertEquals(Protocol.REQ_END, adapter.serveNext());
        done(end);
        assertFalse(session.isOpen());
        assertEquals(List.of(TransferHost.EndReason.COMPLETED), host.ended);
        assertEquals(Protocol.REQ_END, card.readIoSwitch(Protocol.REG_REQUEST), "no session: END");
        assertInstanceOf(TransferException.Abandoned.class, failure(session.volumes()));
    }

    @Test
    void resetMidRequestAbandonsEverythingAtOnce() {
        TransferSession session = begin();
        CompletableFuture<List<String>> handedOut = session.volumes();
        CompletableFuture<List<GuestEntry>> queued = session.list(List.of("VOL"));
        assertEquals(Protocol.REQ_VOLUMES, card.readIoSwitch(Protocol.REG_REQUEST)); // the adapter took it...

        card.onReset(); // ...and RESET came before it answered

        assertInstanceOf(TransferException.Abandoned.class, failure(handedOut));
        assertInstanceOf(TransferException.Abandoned.class, failure(queued));
        assertFalse(session.isOpen());
        assertEquals(List.of(TransferHost.EndReason.RESET), host.ended);
        assertEquals(Protocol.REQ_END, card.readIoSwitch(Protocol.REG_REQUEST));
    }

    @Test
    void aNewBeginSupersedesAnOpenSession() {
        TransferSession first = begin();
        CompletableFuture<List<String>> pending = first.volumes();
        adapter.begin(FakeAdapter.dosLike());
        assertInstanceOf(TransferException.Abandoned.class, failure(pending));
        assertEquals(List.of(TransferHost.EndReason.SUPERSEDED), host.ended);
        assertEquals(2, host.started.size());
        assertEquals("DOS", host.started.get(1).capabilities().osName());
    }

    @Test
    void stashedMemoryComesBackByteForByte() {
        byte[] borrowed = new byte[1600];
        new Random(7).nextBytes(borrowed);
        for (byte b : borrowed) {
            card.writeIoSwitch(Protocol.REG_DATA, b & 0xFF);
        }
        card.writeIoSwitch(Protocol.REG_COMPLETE, Protocol.MSG_STASH);
        card.writeIoSwitch(Protocol.REG_COMPLETE, Protocol.MSG_RECALL);
        byte[] back = new byte[borrowed.length];
        for (int i = 0; i < back.length; i++) {
            back[i] = (byte) card.readIoSwitch(Protocol.REG_DATA);
        }
        assertArrayEquals(borrowed, back);
        assertEquals(0, card.readIoSwitch(Protocol.REG_DATA), "past the end: 0");
    }

    @Test
    void aMalformedReplyFailsTheRequestRatherThanTheCard() {
        TransferSession session = begin();
        CompletableFuture<List<GuestEntry>> list = session.list(List.of("VOL"));
        card.readIoSwitch(Protocol.REG_REQUEST);
        card.writeIoSwitch(Protocol.REG_DATA, 9); // a name of 9 bytes... that never come
        adapter.finish(ResultCode.OK.code());
        assertEquals(ResultCode.IO_ERROR, failure(list).result());
        assertTrue(session.isOpen(), "the session carries on");
    }

    @Test
    void theRomBankRegisterIsRemembered() {
        card.writeIoSwitch(Protocol.REG_STATUS, 3);
        assertEquals(3, card.romBank());
    }

    @Test
    void theBusPassesResetToEveryCard() {
        TransferSession session;
        SlotCard[] slots = new SlotCard[8];
        slots[2] = card;
        try (MotherboardBus bus = MotherboardBus.withoutAudio(slots)) {
            session = begin();
            bus.resetCards();
        }
        assertFalse(session.isOpen());
        assertSame(TransferHost.EndReason.RESET, host.ended.get(0));
    }

    @Test
    void theDirSettingMustNameAFolder() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("dir", "/no/such/folder/here");
        assertThrows(IllegalArgumentException.class, () -> new HostTransferCard().configure(props));
        assertNull(new HostTransferCard().initialDirectory());
    }

    @Test
    void dataBytesOfALongReplyCountAsActivity() throws Exception {
        TransferSession session = begin();
        session.volumes();
        card.readIoSwitch(Protocol.REG_REQUEST); // the adapter takes the request...
        Thread.sleep(30);
        long beforeData = session.nanosSinceAdapterActivity();
        card.writeIoSwitch(Protocol.REG_DATA, 3); // ...and is still sending its reply
        assertTrue(session.nanosSinceAdapterActivity() < beforeData,
            "a data byte is activity, not only polls and completions");
    }

    @Test
    void aListingCanLeaveTheAuxValueToReadAndSayTheSizeIsApproximate() throws Exception {
        TransferSession session = begin();
        adapter.files.put(List.of("VOL", "PROG"), new FakeAdapter.GuestFile("B", 0x0300, 0, new byte[] {1, 2, 3}));
        adapter.auxOnRead.add(List.of("VOL", "PROG"));

        CompletableFuture<List<GuestEntry>> list = session.list(List.of("VOL"));
        adapter.serveNext();
        GuestEntry entry = done(list).get(0);
        assertFalse(entry.auxKnown());
        assertFalse(entry.sizeExact());
        assertEquals(new FileType("B", 0), entry.type());

        CompletableFuture<GuestData> read = session.read(List.of("VOL", "PROG"));
        adapter.serveNext();
        assertEquals(new FileType("B", 0x0300), done(read).type(), "flag 1: the type, learned while reading");
        assertArrayEquals(new byte[] {1, 2, 3}, done(read).bytes());
    }

    @Test
    void printListingEndsTheSessionLikeEnd() throws Exception {
        TransferSession session = begin();
        assertTrue(session.capabilities().printsListing());
        CompletableFuture<Void> listing = session.printListing();
        assertEquals(Protocol.REQ_PRINT_LISTING, adapter.serveNext());
        done(listing);
        assertFalse(session.isOpen());
        assertEquals(List.of(TransferHost.EndReason.COMPLETED), host.ended);
    }

    @Test
    void theTypingRegisterIsTheCardsAndResetClearsIt() {
        assertEquals(0, card.readIoSwitch(Protocol.REG_TYPING), "power-on: not typing, whatever RAM holds");
        card.writeIoSwitch(Protocol.REG_TYPING, 7);
        assertEquals(7, card.readIoSwitch(Protocol.REG_TYPING));
        card.onReset();
        assertEquals(0, card.readIoSwitch(Protocol.REG_TYPING), "RESET abandons a recipe mid-way");
    }
}
