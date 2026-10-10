package com.nordstrom.emulator.transfer;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.expansion.LanguageCard;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A whole machine for the transfer card's integration tests: Language Card,
 * the card in slot 2, a Disk II in slot 6. Emulation runs on the test's
 * thread, one step at a time; host-side work runs on others, as in the
 * application.
 */
final class MachineHarness implements AutoCloseable {

    final HostTransferCard card = new HostTransferCard();
    final BlockingQueue<TransferSession> sessions = new LinkedBlockingQueue<>();
    final MotherboardBus bus;
    private final SystemClock clock;
    private final Deque<Integer> keys = new ConcurrentLinkedDeque<>();
    private double seconds;

    /** @return a test resource copied to a temporary file, or null if it isn't supplied */
    static Path resource(Path dir, String... names) throws IOException {
        for (String name : names) {
            try (InputStream in = MachineHarness.class.getResourceAsStream(name)) {
                if (in != null) {
                    Path file = dir.resolve(name);
                    Files.write(file, in.readAllBytes());
                    return file;
                }
            }
        }
        return null;
    }

    MachineHarness(Path disk) throws IOException {
        card.setHost(new TransferHost() {
            @Override
            public void sessionStarted(TransferSession session) {
                sessions.add(session);
            }

            @Override
            public void sessionEnded(TransferSession session, EndReason reason) {
            }
        });
        SlotCard[] slots = new SlotCard[8];
        slots[0] = new LanguageCard();
        slots[2] = card;
        Disk2Controller disk2 = new Disk2Controller();
        slots[6] = disk2;
        disk2.removableDrives().get(0).insert(disk);
        bus = MotherboardBus.withoutAudio(slots);
        clock = new SystemClock(new Cpu6502(bus, 0xFFFC));
        clock.addCycleListener(bus.videoScanner()::tick);
        clock.addCycleListener(bus.scanlineModes()::tick);
        clock.addCycleListener(disk2::tick);
    }

    void step() {
        if (!keys.isEmpty() && !bus.keyboardRegister().isStrobeSet()) {
            bus.keyboardRegister().keyPressed(keys.poll());
        }
        seconds += clock.step() / 1_023_000.0;
    }

    void run(double s) {
        double until = seconds + s;
        while (seconds < until) {
            step();
        }
    }

    boolean runUntil(String text, double max) {
        double until = seconds + max;
        for (long n = 0; seconds < until; n++) {
            step();
            if (n % 20_000 == 0 && screen().contains(text)) {
                return true;
            }
        }
        return screen().contains(text);
    }

    /** Types text; '\n' is Return. */
    void type(String s) {
        for (char c : s.toCharArray()) {
            keys.add(c == '\n' ? 0x0D : (int) c);
        }
    }

    /** Types a line and waits for it to be taken and run. */
    void enter(String line, double settle) {
        type(line + "\n");
        run(settle);
    }

    String screen() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 24; row++) {
            int base = 0x400 + 0x80 * (row % 8) + 0x28 * (row / 8);
            for (int col = 0; col < 40; col++) {
                int g = bus.read(base + col) & 0x3F;
                sb.append((char) (g < 0x20 ? g + 0x40 : g));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Boots ProDOS 2.4.3 to BASIC.SYSTEM's prompt through Bitsy Bye. */
    boolean bootProDosBasic() {
        if (!runUntil("QUIT.SYSTEM", 60)) {
            return false;
        }
        run(1);
        type("B");
        run(0.5);
        type("B"); // the second B selects BASIC.SYSTEM
        run(0.5);
        type("\n");
        boolean ok = runUntil("]", 30);
        run(1);
        return ok;
    }

    /** Boots the DOS 3.3 System Master, which also loads Integer BASIC into the Language Card. */
    boolean bootDos() {
        boolean ok = runUntil("DOS VERSION 3.3", 30);
        run(25);
        return ok;
    }

    /** The print job collected so far, as host text, taken from the spool. */
    String takePrinted() {
        byte[] job = card.printSpool().takeIfIdle(0);
        return job == null ? "" : new String(PrintText.toHost(job), StandardCharsets.US_ASCII);
    }

    /** Runs a host-side task on its own thread while the emulation runs here. */
    void whileRunning(Runnable task, double max) throws InterruptedException {
        Thread t = new Thread(task, "host-side");
        t.start();
        for (double until = seconds + max; seconds < until && t.isAlive(); ) {
            step();
        }
        run(2);
        t.join(1000);
    }

    /** Types IN#2 and waits for the session it starts; null if none does. */
    TransferSession startSession() {
        type("IN#2\n");
        TransferSession session = null;
        for (double until = seconds + 15; seconds < until && session == null; ) {
            step();
            session = sessions.poll();
        }
        return session;
    }

    @Override
    public void close() {
        bus.close();
    }
}
