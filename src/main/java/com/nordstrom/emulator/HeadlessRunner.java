package com.nordstrom.emulator;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.expansion.LanguageCard;
import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.expansion.VideoTermRenderer;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.PluginLoader;
import com.nordstrom.emulator.system.RemovableMediaDrive;
import com.nordstrom.emulator.system.RomFiles;
import com.nordstrom.emulator.system.ScreenText;
import com.nordstrom.emulator.system.ShiftKeyMod;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SlotCardLoader;
import com.nordstrom.emulator.system.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Runs an Apple II program with no window, from the command line: boots
 * a disk, types at the machine, waits for given text on screen, and
 * writes the screen's text out -- what a person does by hand when running
 * a test program, made scriptable for a Makefile. See
 * {@link RunnerOptions#USAGE} for the command line.
 * <p>
 * The machine is the application's, built the same way: the CPU plugged
 * in through the device between it and the motherboard ({@link CommandRecall}),
 * whose typing feeder types the script's keys through the keyboard cable,
 * paced by the keyboard strobe as Paste is. Its cards are a built-in set,
 * or those a slot configuration names, plug-ins included, loaded as the
 * application loads them. The screen is read the way Copy Screen Text
 * reads it, from whichever display the main window would be showing (the
 * VideoTerm's under its default {@link VideoTerm.Display#SWITCHED} mode,
 * or else the text page or a marked Soft-70 buffer). Nothing watches the
 * CPU: the run waits for what a person would wait for, the screen and,
 * while booting, the drive's IN USE light.
 * <p>
 * It runs unthrottled unless asked to keep real time, with no audio
 * device. The disks are copies, so a run never changes the images it was
 * given or its configuration names, and each run starts from them as they
 * were.
 * <p>
 * About one power-on in 256 the Autostart ROM takes its warm-start path
 * into random memory (see {@code RomBoot} in the tests); a person would
 * switch the machine off and on again, so a boot that doesn't reach the
 * prompt is retried with a fresh power-on, up to {@link #POWER_ONS} in all.
 */
public final class HeadlessRunner {

    static final int FINISHED = 0;
    static final int SETUP_FAILED = 1;
    static final int DIDNT_BOOT = 2;
    static final int TIMED_OUT = 3;
    static final int STOPPED = 4;

    static final int POWER_ONS = 3;
    private static final double CYCLES_PER_SECOND = 1_023_000.0;
    /** How often the screen is looked at: about once a frame. */
    private static final long CHECK_CYCLES = 17_030;
    /** How long the prompt must stand, unchanged, before the boot counts as done. */
    private static final long PROMPT_SETTLE_CYCLES = 1_023_000;
    private static final String SYSTEM_ROM = "system-rom.rom";

    private HeadlessRunner() {}

    /**
     * Entry point; exits with the run's status ({@link RunnerOptions#USAGE}).
     *
     * @param args the command line
     */
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs from a command line.
     *
     * @param args    the command line
     * @param out     where the screen goes without {@code --screen}, and the usage with {@code --help}
     * @param err     where problems are reported
     * @return the exit status
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        RunnerOptions options;
        try {
            options = RunnerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(RunnerOptions.USAGE);
            return SETUP_FAILED;
        }
        if (options.help) {
            out.println(RunnerOptions.USAGE);
            return FINISHED;
        }
        Folders folders;
        try {
            folders = Folders.of(options);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return SETUP_FAILED;
        }
        String problem = setUpRoms(folders.roms());
        if (problem == null && options.drive1 != null) {
            problem = missingDisk(options.drive1, "--d1");
        }
        if (problem == null && options.drive2 != null) {
            problem = missingDisk(options.drive2, "--d2");
        }
        if (problem != null) {
            err.println(problem);
            return SETUP_FAILED;
        }
        ClassLoader cards = HeadlessRunner.class.getClassLoader();
        if (folders.plugins() != null) {
            try {
                cards = PluginLoader.load(folders.plugins(), cards);
            } catch (IOException | IllegalArgumentException e) {
                err.println("Couldn't load plug-ins from " + folders.plugins() + ": " + e.getMessage());
                return SETUP_FAILED;
            }
        }
        Path scratch;
        try {
            scratch = Files.createTempDirectory("replicapple2plus-run");
        } catch (IOException e) {
            err.println("Couldn't make a folder for the disk copies: " + e.getMessage());
            return SETUP_FAILED;
        }
        try {
            return run(options, folders.config(), cards, scratch, out, err);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Where the machine's parts come from: each option given, else the
     * emulator folder's part. The folder is {@code --folder}, else the
     * installed application's remembered one -- but only an explicit
     * {@code --folder} supplies the slot configuration, so a run never
     * depends on what the application was last set up to run.
     *
     * @param roms    the ROM folder, or null for the classpath
     * @param plugins the plug-ins path, or null for none
     * @param config  the slot configuration, or null for the built-in machine
     */
    record Folders(Path roms, String plugins, Path config) {

        static Folders of(RunnerOptions options) {
            Path folder = options.folder;
            if (folder != null && !Files.isDirectory(folder)) {
                throw new IllegalArgumentException("--folder " + folder + " is not a directory");
            }
            if (options.roms != null && !Files.isDirectory(options.roms)) {
                throw new IllegalArgumentException("--roms " + options.roms + " is not a directory");
            }
            if (options.config != null && !Files.isRegularFile(options.config)) {
                throw new IllegalArgumentException("--config " + options.config + " is not a file");
            }
            Path config = options.config;
            if (config == null && folder != null && EmulatorFolder.isSetUp(folder)) {
                config = folder.resolve(EmulatorFolder.CONFIGURATION);
            }
            if (config != null && (options.videoTerm || options.languageCard)) {
                throw new IllegalArgumentException("--videoterm and --language-card add to the built-in machine; "
                    + config + " names this machine's cards");
            }
            if (config == null && options.drive1 == null) {
                throw new IllegalArgumentException("--d1 is required: the disk to boot (" + folder
                    + " holds no " + EmulatorFolder.CONFIGURATION + " to name one)");
            }
            Path base = folder != null ? folder : EmulatorFolder.remembered();
            Path roms = options.roms != null ? options.roms : base != null ? base.resolve("roms") : null;
            String plugins = options.plugins;
            if (plugins == null && base != null && Files.isDirectory(base.resolve("plugins"))) {
                plugins = base.resolve("plugins").toString();
            }
            return new Folders(roms, plugins, config);
        }
    }

    /** @return what's wrong with the ROM folder, or null if the system ROM can be found */
    private static String setUpRoms(Path roms) {
        RomFiles.setDirectory(roms); // before anything loads a ROM
        try (InputStream in = RomFiles.open(RomFiles.class, SYSTEM_ROM)) {
            return in == null ? RomFiles.missing(SYSTEM_ROM) : null;
        } catch (IOException e) {
            return "Couldn't read " + SYSTEM_ROM + ": " + e.getMessage();
        }
    }

    private static String missingDisk(Path disk, String flag) {
        return Files.isRegularFile(disk) ? null : flag + " " + disk + " is not a file";
    }

    private static int run(RunnerOptions options, Path config, ClassLoader cards, Path scratch,
                           PrintStream out, PrintStream err) {
        Machine machine = null;
        try {
            Machine.Outcome booted = null;
            for (int powerOn = 1; powerOn <= POWER_ONS; powerOn++) {
                if (machine != null) {
                    machine.close();
                    err.println("Power-on " + (powerOn - 1) + " didn't reach the " + options.prompt + " prompt ("
                        + machine.describe(booted) + "); switching off and on again");
                }
                machine = new Machine(options, config, cards, scratch);
                booted = machine.runUntil(machine.promptStands(), options.bootTimeout);
                if (booted == Machine.Outcome.MET) {
                    break;
                }
            }
            if (booted != Machine.Outcome.MET) {
                err.println("The machine didn't reach the " + options.prompt + " prompt in " + POWER_ONS + " power-ons ("
                    + machine.describe(booted) + ")");
                return write(machine, options, out, err, DIDNT_BOOT);
            }

            long deadline = machine.cycles() + (long) (options.timeout * CYCLES_PER_SECOND);
            for (RunnerOptions.Step step : options.steps) {
                if (step instanceof RunnerOptions.Type type) {
                    machine.type(type.segments());
                } else if (step instanceof RunnerOptions.Expect expect) {
                    int status = await(machine, expect.text(), deadline, options, out, err, "--expect");
                    if (status != FINISHED) {
                        return status;
                    }
                } else if (step instanceof RunnerOptions.Wait wait) {
                    int status = pause(machine, wait.seconds(), deadline, options, out, err);
                    if (status != FINISHED) {
                        return status;
                    }
                }
            }
            int status = await(machine, options.until, deadline, options, out, err, "--until");
            return status != FINISHED ? status : write(machine, options, out, err, FINISHED);
        } catch (IOException e) {
            err.println("Couldn't set up the disks: " + e.getMessage());
            return SETUP_FAILED;
        } catch (RuntimeException | LinkageError e) {
            // a card or ROM that can't be set up: a missing VideoTerm ROM, a configuration naming no such card,
            // a plug-in built against another version
            Throwable cause = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
            err.println("Couldn't set up the machine" + (config != null ? " from " + config : "") + ": "
                + cause.getMessage());
            return SETUP_FAILED;
        } finally {
            if (machine != null) {
                machine.close();
            }
        }
    }

    /**
     * Runs until {@code text} is on screen.
     *
     * @return {@link #FINISHED} if it appeared; otherwise the run's final status, with the screen written
     */
    private static int await(Machine machine, String text, long deadline, RunnerOptions options,
                             PrintStream out, PrintStream err, String flag) {
        double seconds = Math.max(0, deadline - machine.cycles()) / CYCLES_PER_SECOND;
        Machine.Outcome outcome = machine.runUntil(screen -> screen.contains(text), seconds);
        if (outcome == Machine.Outcome.MET) {
            return FINISHED;
        }
        err.println(flag + " \"" + text + "\" not seen: " + machine.describe(outcome));
        return write(machine, options, out, err, outcome == Machine.Outcome.STOPPED ? STOPPED : TIMED_OUT);
    }

    /**
     * Runs on for {@code seconds}, within what's left of the run's time.
     *
     * @return {@link #FINISHED} once the time has passed; otherwise the run's final status, with the screen written
     */
    private static int pause(Machine machine, double seconds, long deadline, RunnerOptions options,
                             PrintStream out, PrintStream err) {
        double left = Math.max(0, deadline - machine.cycles()) / CYCLES_PER_SECOND;
        Machine.Outcome outcome = machine.runUntil(screen -> false, Math.min(seconds, left));
        if (outcome == Machine.Outcome.STOPPED) {
            err.println("--wait " + seconds + ": " + machine.describe(outcome));
            return write(machine, options, out, err, STOPPED);
        }
        if (seconds > left) {
            err.println("--wait " + seconds + " runs past --timeout: " + machine.describe(outcome));
            return write(machine, options, out, err, TIMED_OUT);
        }
        return FINISHED;
    }

    /** Writes the screen's text out, and returns {@code status} -- or {@link #SETUP_FAILED} if it can't be written. */
    private static int write(Machine machine, RunnerOptions options, PrintStream out, PrintStream err, int status) {
        String text = machine.screen() + System.lineSeparator();
        if (options.screen == null) {
            out.print(text);
            out.flush();
            return status;
        }
        try {
            Files.writeString(options.screen, text, StandardCharsets.US_ASCII);
            return status;
        } catch (IOException | UncheckedIOException e) {
            err.println("Couldn't write the screen to " + options.screen + ": " + e.getMessage());
            return SETUP_FAILED;
        }
    }

    private static void deleteQuietly(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            // a leftover temporary folder is harmless
        }
    }

    /** One power-on of the machine the options describe. */
    private static final class Machine implements AutoCloseable {

        enum Outcome { MET, TIMED_OUT, STOPPED }

        private final SlotCard[] slots;
        private final MotherboardBus bus;
        private final VideoTerm videoTerm;
        private final Disk2Controller disk;
        private final TypingFeeder typing;
        private final SystemClock clock;
        private final String prompt;
        private final RealTime realTime;
        private String stopReason;

        Machine(RunnerOptions options, Path config, ClassLoader cards, Path scratch) throws IOException {
            slots = config != null ? load(config, cards) : builtIn(options);
            disk = bootController(slots);
            for (int slot = 0; slot < slots.length; slot++) {
                if (slots[slot] != null) {
                    copyMedia(slots[slot], slot, options, scratch);
                }
            }
            if (options.drive1 != null) {
                disk.removableDrives().get(0).insert(copy(options.drive1, scratch, "d1"));
            }
            if (options.drive2 != null) {
                disk.removableDrives().get(1).insert(copy(options.drive2, scratch, "d2"));
            }
            if (!disk.removableDrives().get(0).isPresent()) {
                throw new IllegalStateException("drive 1 has no disk to boot: give --d1, or drive1= in the configuration");
            }
            VideoTerm found = null;
            for (SlotCard card : slots) {
                if (card instanceof VideoTerm v) {
                    found = v;
                    break;
                }
            }
            videoTerm = found;
            prompt = options.prompt;
            realTime = options.realTime ? new RealTime(System::nanoTime, RealTime::sleep) : null;
            bus = MotherboardBus.withoutAudio(slots);
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW2); // the application's default, not this host's remembered choice
            CommandRecall recall = new CommandRecall(bus, bus.keyboardRegister(), new CommandHistory(), bus.shiftKeyMod());
            typing = recall.typing();
            clock = new SystemClock(new Cpu6502(recall, 0xFFFC));
            clock.addCycleListener(bus.videoScanner()::tick);
            clock.addCycleListener(bus.scanlineModes()::tick);
            clock.addCycleListener(bus.paddleTimers()::tick);
            for (SlotCard card : slots) {
                if (card != null) {
                    clock.addCycleListener(card::tick);
                }
            }
            clock.addCycleListener(typing::tick);
        }

        /** A configuration that can't be read is a setup problem like any other, not a disk's. */
        private static SlotCard[] load(Path config, ClassLoader cards) {
            try {
                return SlotCardLoader.load(config, cards);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static SlotCard[] builtIn(RunnerOptions options) {
            SlotCard[] slots = new SlotCard[8];
            if (options.languageCard) {
                slots[0] = new LanguageCard();
            }
            if (options.videoTerm) {
                slots[3] = new VideoTerm();
            }
            slots[6] = new Disk2Controller();
            for (SlotCard card : slots) {
                if (card != null) {
                    card.configure(new Properties());
                }
            }
            return slots;
        }

        /** The Autostart ROM boots the highest-numbered slot holding a disk controller. */
        private static Disk2Controller bootController(SlotCard[] slots) {
            for (int slot = slots.length - 1; slot >= 0; slot--) {
                if (slots[slot] instanceof Disk2Controller controller) {
                    return controller;
                }
            }
            throw new IllegalStateException("there's no Disk II controller to boot from");
        }

        /**
         * Puts copies in place of the images a configuration loaded, so the
         * run can't write to them. Drives {@code --d1} and {@code --d2} will
         * fill are left for them.
         */
        private void copyMedia(SlotCard card, int slot, RunnerOptions options, Path scratch) throws IOException {
            List<RemovableMediaDrive> drives = card.removableDrives();
            for (int i = 0; i < drives.size(); i++) {
                RemovableMediaDrive drive = drives.get(i);
                boolean replaced = card == disk && (i == 0 ? options.drive1 != null : i == 1 && options.drive2 != null);
                if (replaced || drive.currentImagePath().isEmpty()) {
                    continue;
                }
                drive.insert(copy(drive.currentImagePath().get(), scratch, "slot" + slot + "-drive" + (i + 1)));
            }
        }

        /** Each drive gets its own folder, so two images with one name don't collide; the name keeps its format. */
        private static Path copy(Path image, Path scratch, String drive) throws IOException {
            Path folder = Files.createDirectories(scratch.resolve(drive));
            return Files.copy(image, folder.resolve(image.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }

        long cycles() {
            return clock.cycleCount();
        }

        /** @return the text of the display the main window would show */
        String screen() {
            if (videoTerm != null && videoTerm.shownInMainWindow(bus.videoSoftSwitches())) {
                return VideoTermRenderer.text(videoTerm);
            }
            return ScreenText.capture(bus, bus.scanlineModes().completedFrame());
        }

        void type(List<Object> segments) {
            for (Object segment : segments) {
                if (segment instanceof String text) {
                    typing.enqueue(text);
                } else {
                    typing.enqueueKeys((Integer) segment);
                }
            }
        }

        /**
         * Runs until the screen passes {@code test}, which is shown it about
         * once a frame.
         *
         * @param test    what the screen is waiting for
         * @param seconds the most emulated time to run
         * @return whether the screen passed, time ran out, or the machine stopped
         */
        Outcome runUntil(Predicate<String> test, double seconds) {
            long limit = clock.cycleCount() + (long) (seconds * CYCLES_PER_SECOND);
            long nextCheck = clock.cycleCount() + CHECK_CYCLES;
            while (true) {
                if (clock.cycleCount() >= nextCheck) {
                    if (realTime != null) {
                        realTime.keepPace(clock.cycleCount());
                    }
                    if (test.test(screen())) {
                        return Outcome.MET;
                    }
                    if (clock.cycleCount() >= limit) {
                        return Outcome.TIMED_OUT;
                    }
                    nextCheck = clock.cycleCount() + CHECK_CYCLES;
                }
                int cycles;
                try {
                    cycles = clock.step();
                } catch (UnsupportedOperationException e) {
                    stopReason = e.getMessage(); // an opcode this CPU doesn't model
                    return Outcome.STOPPED;
                }
                if (cycles == 0) {
                    stopReason = "the CPU halted"; // a halted CPU's steps take no time
                    return Outcome.STOPPED;
                }
            }
        }

        /**
         * The boot is done once the screen's last line is the prompt
         * (Applesoft's {@code ]}, or CP/M's {@code A>}, say), the drive has
         * stopped, and both have stood unchanged for a second -- what a
         * person waits for. The drive matters: DOS shows the prompt while it
         * loads the HELLO program, which can take longer than a second,
         * before HELLO clears the screen.
         */
        Predicate<String> promptStands() {
            return new Predicate<>() {
                private String standing;
                private long since;

                @Override
                public boolean test(String screen) {
                    if (disk.isMotorOn() || !(screen.endsWith("\n" + prompt) || screen.equals(prompt))) {
                        standing = null;
                        return false;
                    }
                    if (!screen.equals(standing)) {
                        standing = screen;
                        since = clock.cycleCount();
                        return false;
                    }
                    return clock.cycleCount() - since >= PROMPT_SETTLE_CYCLES;
                }
            };
        }

        String describe(Outcome outcome) {
            double seconds = clock.cycleCount() / CYCLES_PER_SECOND;
            return outcome == Outcome.STOPPED
                ? String.format("stopped after %.1f s: %s", seconds, stopReason)
                : String.format("timed out after %.1f s", seconds);
        }

        /** Lets go of what the cards hold on the host -- a listening port, say -- for the next power-on's cards. */
        @Override
        public void close() {
            for (SlotCard card : slots) {
                if (card != null) {
                    card.release();
                }
            }
            bus.close();
        }
    }

    /**
     * Holds emulated time to real time at the II+'s clock rate, for runs
     * that deal with the world outside in real time -- a server's timeouts
     * don't stretch to suit an unthrottled guest. Like the application's
     * own pacing, a run that falls well behind (the host was busy, or
     * suspended) starts keeping time afresh rather than sprinting to catch
     * up, so a burst never runs faster than a II+.
     */
    static final class RealTime {

        /** How far behind starts afresh: the application's limit, five of its 20 ms ticks. */
        static final long BEHIND_LIMIT_NANOS = 100_000_000L;
        private static final double NANOS_PER_CYCLE = 1e9 / CYCLES_PER_SECOND;

        /** Waits for a number of nanoseconds. */
        interface Sleeper {
            void sleep(long nanos) throws InterruptedException;
        }

        private final LongSupplier nanoTime;
        private final Sleeper sleeper;
        private boolean started;
        private long startNanos;
        private long startCycles;

        RealTime(LongSupplier nanoTime, Sleeper sleeper) {
            this.nanoTime = nanoTime;
            this.sleeper = sleeper;
        }

        /**
         * Waits until real time has caught up with {@code cycles} of emulated time.
         *
         * @param cycles the machine's cycle count now
         */
        void keepPace(long cycles) {
            long now = nanoTime.getAsLong();
            if (!started) {
                restart(now, cycles);
                return;
            }
            long wait = startNanos + Math.round((cycles - startCycles) * NANOS_PER_CYCLE) - now;
            if (wait > 0) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (wait < -BEHIND_LIMIT_NANOS) {
                restart(now, cycles);
            }
        }

        private void restart(long now, long cycles) {
            started = true;
            startNanos = now;
            startCycles = cycles;
        }

        static void sleep(long nanos) throws InterruptedException {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        }
    }
}
