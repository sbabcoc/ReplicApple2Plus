package com.nordstrom.emulator;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.input.BusInputSink;
import com.nordstrom.emulator.input.InputMapper;
import com.nordstrom.emulator.input.InputMapping;
import com.nordstrom.emulator.input.JamepadProvider;
import com.nordstrom.emulator.input.NetworkPadProvider;
import com.nordstrom.emulator.input.PadProvider;
import com.nordstrom.emulator.input.PadPoller;
import com.nordstrom.emulator.input.PadSnapshot;
import com.nordstrom.emulator.system.CliArgs;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.PluginLoader;
import com.nordstrom.emulator.system.RemovableMediaDrive;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SlotCardLoader;
import com.nordstrom.emulator.system.SystemClock;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The application's entry point. Assembles a real, running machine.
 * <p>
 * Slot population goes through this project's own existing
 * {@link SlotCardLoader}/INI configuration mechanism -- the same one
 * {@code SlotConfigTemplate} and {@code CardCatalog} already use --
 * rather than a parallel, Apple2Plus-specific one. With no
 * {@code --config} given, slots stay entirely empty, matching how this
 * class behaved before disk support existed at all: no cards, straight
 * to Applesoft/the Monitor via the real, unmodified {@code $FFFC}
 * Autostart ROM path.
 * <p>
 * A {@link Disk2Controller} being present with no disk actually
 * inserted is a genuine, confirmed problem, not a style preference:
 * the real Autostart ROM recognizes a real Disk II boot ROM signature
 * the moment the card exists at all, and immediately attempts to boot
 * from it -- with no disk, {@link Disk2Controller#tick} supplies a
 * constant stream of zero pulses (no weak-bit randomization is modeled
 * for the no-disk-at-all case), which real boot code, correctly
 * waiting for sync bytes real spinning media would eventually produce,
 * waits for forever. {@link SlotCardLoader}'s own configuration
 * mechanism already handles this correctly on its own: an unconfigured
 * slot is left {@code null}, and {@link Disk2Controller#configure}
 * only inserts a disk when the config file's {@code drive1}/{@code
 * drive2} keys actually name one -- so the safety property this class
 * needs (never boot with a present-but-empty disk card) falls out of
 * the existing mechanism rather than needing anything special here.
 * <p>
 * Whichever slot (if any) the configuration puts a
 * {@link Disk2Controller} in is scanned for after loading, not assumed
 * to be slot 6 -- slot 6 is the real, conventional choice, but nothing
 * requires it, and this class has no reason to hardcode an assumption
 * the config file itself doesn't have to share.
 * <p>
 * When a {@link Disk2Controller} is found, a "Disk" menu
 * ({@link DiskMenu}) is added to let the disk in either drive be
 * swapped while the emulator runs -- the same
 * {@link RemovableMediaDrive#insert} call {@link Disk2Controller#configure}
 * itself already uses at startup, not a separate mechanism. With no
 * disk card at all, there's nothing this menu could operate on, so
 * it's simply not added, rather than shown disabled.
 * <p>
 * Cycle pacing: this class, not {@link SystemClock}, decides how fast
 * to run -- {@link SystemClock} deliberately has no opinion about
 * real-time pacing at all (see its own Javadoc). The machine runs on
 * its own thread ({@link EmulationLoop}), paced by the audio device: it
 * runs {@link #CYCLES_PER_TICK} cycles, hands that tick's audio to the
 * sound card, and blocks there whenever the card is full, which locks
 * emulated time to real time. It replaced a Swing-timer design that
 * measurably ran at about 83% speed and let the audio line run dry.
 * Because that thread owns all emulator state, the event thread never
 * touches it directly: keystrokes and disk changes are posted to the
 * loop and run between ticks.
 * <p>
 * Game input: paddles and pushbuttons are driven by a gamepad, mapped
 * per {@link InputMapping} (built-in defaults, or a custom file given
 * with {@code --input}). Gamepad support is optional and adds no
 * dependency: it needs the Jamepad jar in the {@code --plugins}
 * directory or on the class path, and without it everything else works
 * exactly as before. With no pad connected the paddles read as
 * unplugged (255), exactly as real hardware does with nothing in the
 * game port; a connected pad at rest reads centered (128).
 * <p>
 * {@code --network-input PORT} selects {@link NetworkPadProvider} instead
 * of Jamepad -- the route for Android/Termux/PRoot, where an
 * unprivileged process has no device-node access at all (confirmed
 * directly: {@code ls -l /dev} inside that environment shows nothing),
 * ruling out Jamepad's SDL backend regardless of which native library it
 * ships. A companion Android app reads the real controller through
 * Android's own APIs and sends its state here over loopback UDP. The two
 * providers are mutually exclusive by design, not combined or
 * auto-detected between: {@code --network-input} always wins if given,
 * so which one is active is always exactly what was asked for on the
 * command line, never a guess.
 * <p>
 * A gap hit during interactive use (an address range this project
 * hasn't modeled yet, say) is reported and stops emulation cleanly
 * rather than crashing with an unhandled exception -- the window stays
 * open and showing whatever was last drawn, rather than vanishing.
 * <p>
 * REBOOT: tears down and rebuilds everything volatile -- slots, bus,
 * CPU, clock, screen, emulation loop, gamepad input wiring -- exactly
 * as if the process had just launched, while preserving whatever disk
 * media is currently inserted (a real Apple II power-cycle doesn't
 * eject a floppy either). See {@link #buildMachine} for what gets
 * rebuilt and {@link #createAndRun}'s own {@code onReboot} lambda for
 * the teardown/rebuild/rewire sequence itself. The frame, and the pad
 * poller's underlying controller connection's *configuration* (not its
 * live connection -- see {@link #buildMachine}'s own note on that), are
 * the only things this project treats as surviving a reboot the way
 * they'd survive a real power-cycle too: the physical window and the
 * physical controller don't go anywhere.
 */
public final class Apple2Plus {

    private static final int FRAME_INTERVAL_MS = 20; // ~50 Hz
    private static final int CYCLES_PER_TICK = 20_000; // ~1.023 MHz * 20ms, approximately
    private static final long TICK_NANOS = FRAME_INTERVAL_MS * 1_000_000L; // fallback pacing only: no audio device
    private static final long PAD_POLL_INTERVAL_MS = 10; // 100 Hz: far faster than any game reads a paddle
    private static final long SHUTDOWN_JOIN_MS = 300; // longest the exit hook (or a reboot) waits for the loop to stop
    private static final int FLASH_TOGGLE_EVERY_N_TICKS = 15; // roughly twice a second at 50 Hz
    private static final String USAGE = "Usage: Apple2Plus [--config slots.ini] [--plugins DIR] [--input input.ini] [--network-input PORT]";

    /**
     * Application entry point.
     *
     * @param args {@code [--config slots.ini] [--plugins DIR] [--input input.ini] [--network-input PORT]},
     *             all optional and independent -- see this class's own
     *             Javadoc for what happens with neither
     */
    public static void main(String[] args) {
        CliArgs cli;
        try {
            cli = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(() -> createAndRun(cli));
    }

    /**
     * Everything that gets torn down and rebuilt on reboot --
     * deliberately NOT the {@code JFrame} itself, nor the shared,
     * reboot-independent configuration ({@code cli}, {@code
     * classLoader}, {@code inputMapping}) {@link #buildMachine} closes
     * over instead. A plain holder, not a class with behavior of its
     * own: every operation on these pieces already belongs to them
     * individually.
     *
     * @param disk the disk controller found in {@code slots}, or null if none was configured
     * @param bus the motherboard bus this machine's CPU, screen, and keyboard register all share
     * @param screen the panel currently showing this machine's video output
     * @param loop the emulation thread currently running this machine
     * @param padPoller the thread currently polling a gamepad into this machine
     * @param toolbar the toolbar currently wired to this machine's CPU
     */
    private record Machine(Disk2Controller disk, MotherboardBus bus, ScreenPanel screen, EmulationLoop loop,
                            PadPoller padPoller, JToolBar toolbar) {}

    private static void createAndRun(CliArgs cli) {
        ClassLoader classLoader = Apple2Plus.class.getClassLoader();
        String pluginsDir = cli.get("plugins");
        if (pluginsDir != null) {
            try {
                classLoader = PluginLoader.load(Path.of(pluginsDir), classLoader);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("Could not load plugins: " + e.getMessage());
                System.exit(1);
                return;
            }
        }

        InputMapping inputMapping;
        String inputFile = cli.get("input");
        try {
            inputMapping = (inputFile != null) ? InputMapping.load(Path.of(inputFile)) : InputMapping.defaults();
        } catch (NoSuchFileException e) {
            System.err.println("Could not load input configuration: file not found: " + e.getFile());
            System.exit(1);
            return;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Could not load input configuration: " + e.getMessage());
            System.exit(1);
            return;
        }

        JFrame frame = new JFrame("ReplicApple2Plus");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);

        final ClassLoader finalClassLoader = classLoader;
        final InputMapping finalInputMapping = inputMapping;

        // Mutable only across a reboot -- read by the shutdown hook from a
        // different thread, hence AtomicReference rather than a plain field.
        AtomicReference<Machine> current = new AtomicReference<>();

        // A one-element holder, not a plain Runnable field, specifically so
        // the lambda below can pass itself to buildMachine when rebuilding
        // the toolbar on reboot -- a lambda cannot refer to the local
        // variable it is itself being assigned to (fails Java's definite
        // assignment check), but it CAN refer to a separately-declared,
        // already-initialized holder that happens to contain it.
        Runnable[] onRebootHolder = new Runnable[1];
        onRebootHolder[0] = () -> {
            Machine old = current.get();
            old.padPoller().stop(SHUTDOWN_JOIN_MS);
            old.loop().stop(SHUTDOWN_JOIN_MS);
            // After the loop stops (so the emulation thread can't be
            // concurrently writing the same track data this reads), and
            // before buildMachine below captures old.disk()'s media for
            // the fresh machine -- a write made just before Reboot must
            // not be silently lost the same way the whole point of
            // persisting at all is to avoid.
            persistDiskQuietly(old.disk());
            Machine fresh = buildMachine(cli, finalClassLoader, finalInputMapping, old.disk(), frame, onRebootHolder[0]);
            rewireFrame(frame, old, fresh);
            current.set(fresh);
            fresh.loop().start();
        };
        Runnable onReboot = onRebootHolder[0];

        Machine initial = buildMachine(cli, finalClassLoader, finalInputMapping, null, frame, onReboot);
        current.set(initial);
        rewireFrame(frame, null, initial);

        // Runs on both a normal EXIT_ON_CLOSE-triggered exit and a
        // SIGINT/SIGTERM (e.g. Ctrl+C from a terminal) -- shutdown hooks
        // cover either path, unlike relying on JFrame's own close
        // handling alone, which only fires for the window-close case.
        // Reads `current` at the moment of shutdown, not at hook-registration
        // time, so this closes down whichever machine (original or a later
        // reboot's) actually happens to be running when the JVM exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Machine m = current.get();
            m.padPoller().stop(SHUTDOWN_JOIN_MS);
            m.loop().stop(SHUTDOWN_JOIN_MS);
            persistDiskQuietly(m.disk()); // after the loop stops, same reasoning as the reboot lambda above
        }));

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.requestFocusInWindow();

        if (initial.disk() != null && !initial.disk().removableDrives().get(0).isPresent()) {
            promptForBootDisk(initial.disk(), frame);
        }

        initial.loop().start();
    }

    /**
     * Builds one complete, running machine: slots, bus, CPU, clock,
     * screen, emulation loop, and gamepad wiring -- everything
     * {@link #createAndRun} originally built inline, now shared with
     * its own {@code onReboot} lambda so the two can never silently
     * drift apart into two different ways of constructing "a machine."
     * <p>
     * {@code mediaSource}, when given (a reboot, not initial startup),
     * supplies the disk(s) to restore: before anything else, whatever is
     * currently loaded in each of its drives is captured, and -- once
     * the fresh {@link Disk2Controller} exists -- re-inserted into the
     * corresponding drive here, matching how a real Apple II power-cycle
     * doesn't eject a floppy. {@code null} on initial startup, where
     * {@link SlotCardLoader}'s own {@code driveN=} config handling (and,
     * failing that, {@link #promptForBootDisk}) already covers it.
     * <p>
     * {@code onReboot}, when non-null, is wired into this machine's
     * toolbar REBOOT button. {@code null} is accepted (and simply means
     * "no toolbar yet") only because the reboot sequence itself needs a
     * machine built before it has anywhere to put a REBOOT button that
     * reboots -- in practice every real {@code Machine} this class keeps
     * around has a real toolbar; see {@link #rewireFrame}, which is the
     * only caller that actually uses the returned toolbar.
     * <p>
     * Not preserved across a rebuild, by design, not oversight: the pad
     * poller's underlying controller connection. {@link PadPoller}'s
     * {@code InputMapper} is set once, at construction, with no way to
     * swap it later -- so a fresh {@link InputMapper} here means a fresh
     * {@link PadPoller}, which means briefly reconnecting to the real
     * controller (re-initializing SDL, or re-binding a UDP socket).
     * Acceptable for a deliberate, infrequent action like Reboot; not
     * something to redesign {@link PadPoller}'s API around.
     *
     * @param cli the parsed command line, unchanged across a reboot
     * @param classLoader plugins classloader (or the application's own), unchanged across a reboot
     * @param inputMapping the gamepad mapping configuration, unchanged across a reboot
     * @param mediaSource the previous machine's disk controller, to carry its loaded media forward, or null on initial startup
     * @param frame the single, reused application window, needed only to wire this machine's toolbar's REBOOT button
     * @param onReboot runs when this machine's REBOOT button is clicked, or null if this machine will never have a toolbar
     * @return the fully built, not-yet-started machine
     */
    private static Machine buildMachine(CliArgs cli, ClassLoader classLoader, InputMapping inputMapping,
                                         Disk2Controller mediaSource, JFrame frame, Runnable onReboot) {
        SlotCard[] slots;
        String configFile = cli.get("config");
        if (configFile != null) {
            try {
                slots = SlotCardLoader.load(Path.of(configFile), classLoader);
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                // The same configuration already loaded successfully once
                // (at initial startup) if mediaSource != null -- a failure
                // here on reboot would mean the config file changed or
                // vanished out from under a running process, which is
                // exceptional enough to surface loudly rather than attempt
                // any fallback.
                throw new IllegalStateException("Could not reload slot configuration on reboot: " + e.getMessage(), e);
            }
        } else {
            slots = new SlotCard[8]; // no config given -- no cards at all
        }

        Disk2Controller disk = null;
        for (SlotCard card : slots) {
            if (card instanceof Disk2Controller d) {
                disk = d;
                break;
            }
        }

        if (disk != null && mediaSource != null) {
            List<RemovableMediaDrive> oldDrives = mediaSource.removableDrives();
            List<RemovableMediaDrive> newDrives = disk.removableDrives();
            for (int i = 0; i < oldDrives.size() && i < newDrives.size(); i++) {
                RemovableMediaDrive newDrive = newDrives.get(i);
                int driveNumber = i + 1;
                oldDrives.get(i).currentImagePath().ifPresent(path -> {
                    try {
                        newDrive.insert(path);
                    } catch (IOException e) {
                        System.err.println("Could not restore disk into drive " + driveNumber + " after reboot: " + e.getMessage());
                    }
                });
            }
        }

        MotherboardBus bus = new MotherboardBus(slots);
        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC); // the real, unmodified Autostart reset vector
        SystemClock clock = new SystemClock(cpu);
        clock.addCycleListener(bus.videoScanner()::tick);
        clock.addCycleListener(bus.scanlineModes()::tick);
        clock.addCycleListener(bus.speakerOutput()::tick);
        clock.addCycleListener(bus.paddleTimers()::tick);
        if (disk != null) {
            clock.addCycleListener(disk::tick);
        }
        ScreenPanel screen = new ScreenPanel(bus, bus.scanlineModes());

        int[] ticksSinceFlash = {0}; // touched only by the emulation thread
        EmulationLoop loop = new EmulationLoop(clock::step, CYCLES_PER_TICK, TICK_NANOS,
            bus.speakerOutput()::flush, bus.speakerOutput()::hasDevice,
            () -> {
                if (++ticksSinceFlash[0] >= FLASH_TOGGLE_EVERY_N_TICKS) {
                    ticksSinceFlash[0] = 0;
                    SwingUtilities.invokeLater(screen::toggleFlash); // flash state belongs to the paint thread
                }
                screen.repaint(); // safe from any thread
            });

        // Game input. The mapper is primed with an absent pad right away,
        // before any pad is even looked for, so the paddles start out
        // unplugged (reading 255, like an empty game port) and stay that
        // way until a pad actually connects; the sink queues that for the
        // emulation thread, which runs it first thing.
        InputMapper inputMapper = new InputMapper(inputMapping, new BusInputSink(bus, loop));
        inputMapper.apply(PadSnapshot.ABSENT);
        final ClassLoader providerLoader = classLoader;
        String networkInputPort = cli.get("network-input");
        Supplier<PadProvider> padProviderFactory = (networkInputPort != null)
            ? () -> NetworkPadProvider.create(Integer.parseInt(networkInputPort), System.err)
            : () -> JamepadProvider.create(providerLoader, System.err);
        PadPoller padPoller = new PadPoller(padProviderFactory, inputMapper, PAD_POLL_INTERVAL_MS, System.err);
        padPoller.start();

        JToolBar toolbar = (onReboot != null) ? ToolbarControls.build(cpu, loop, onReboot) : null;

        return new Machine(disk, bus, screen, loop, padPoller, toolbar);
    }

    /**
     * Swaps {@code frame}'s contents from {@code previous} (if any) to
     * {@code next} -- the screen, the toolbar, the keyboard listener, and
     * the disk menu (if a disk controller is present). Does not call
     * {@code frame.pack()}: the new screen has the same preferred size as
     * the old one (same video hardware, unchanged configuration), so
     * revalidating in place avoids an unnecessary resize/flicker that
     * {@code pack()} would otherwise cause on every reboot.
     *
     * @param frame the single, reused application window
     * @param previous the machine whose components are being removed, or null on initial startup
     * @param next the machine whose components are being installed
     */
    /**
     * Persists {@code disk}'s loaded media (if any), logging rather than
     * propagating a failure -- called from the reboot lambda and the
     * shutdown hook, neither of which has anywhere to usefully route a
     * thrown exception, and in both cases the alternative to "log and
     * move on" is "the application fails to reboot or exit at all over
     * a disk write that didn't save," which is worse than losing that
     * one write with a visible warning about it.
     *
     * @param disk the controller to persist, or null if no disk card was configured at all
     */
    private static void persistDiskQuietly(Disk2Controller disk) {
        if (disk == null) {
            return;
        }
        try {
            disk.persist();
        } catch (IOException e) {
            System.err.println("Could not persist a disk write: " + e.getMessage());
        }
    }

    private static void rewireFrame(JFrame frame, Machine previous, Machine next) {
        if (previous != null) {
            frame.getContentPane().remove(previous.screen());
            frame.getContentPane().remove(previous.toolbar());
            for (var listener : frame.getKeyListeners()) {
                frame.removeKeyListener(listener);
            }
        }

        frame.getContentPane().add(next.screen());
        frame.getContentPane().add(next.toolbar(), BorderLayout.NORTH);

        if (next.disk() != null) {
            JMenuBar menuBar = new JMenuBar();
            menuBar.add(DiskMenu.build(next.disk(), frame, next.loop()));
            frame.setJMenuBar(menuBar);
        } else {
            frame.setJMenuBar(null);
        }

        KeyboardInputListener keyboardInput = new KeyboardInputListener(next.bus().keyboardRegister(), next.loop());
        frame.addKeyListener(keyboardInput);
        frame.setFocusTraversalKeysEnabled(false); // don't let Tab escape focus -- real software may want it

        frame.getContentPane().revalidate();
        frame.getContentPane().repaint();
        frame.requestFocusInWindow();
    }

    private Apple2Plus() {}

    /**
     * Prompts for a disk to boot from, before emulation starts, when a
     * {@link Disk2Controller} is present but drive 1 is empty --
     * exactly the configuration that would otherwise hang silently
     * waiting for sync bytes that never arrive (see this class's own
     * Javadoc). Reuses {@link DiskMenu#promptAndInsert} rather than a
     * second copy of the same file-chooser-plus-error-handling logic.
     * If the chooser is dismissed with nothing selected, emulation
     * still starts -- this is a courtesy, not an enforced requirement;
     * the same hang is still possible, but the user was actually given
     * the chance to avoid it, which is the whole point.
     *
     * @param disk the controller whose drive 1 needs an initial disk
     * @param parent the component to anchor dialogs to
     */
    private static void promptForBootDisk(Disk2Controller disk, JFrame parent) {
        JOptionPane.showMessageDialog(parent,
            "A disk drive is configured but empty. Select a disk to boot from,\n"
            + "or Cancel to start with no disk -- real boot code will keep\n"
            + "waiting for one, but you can insert one later from the Disk menu.",
            "No Disk Loaded", JOptionPane.INFORMATION_MESSAGE);
        DiskMenu.promptAndInsert(disk.removableDrives().get(0), parent, Runnable::run); // emulation hasn't started yet
    }
}
