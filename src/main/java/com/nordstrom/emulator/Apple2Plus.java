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
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SlotCardLoader;
import com.nordstrom.emulator.system.SystemClock;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
 * {@link com.nordstrom.emulator.system.RemovableMediaDrive#insert} call
 * {@link Disk2Controller#configure} itself already uses at startup, not
 * a separate mechanism. With no disk card at all, there's nothing this
 * menu could operate on, so it's simply not added, rather than shown
 * disabled.
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
 */
public final class Apple2Plus {

    private static final int FRAME_INTERVAL_MS = 20; // ~50 Hz
    private static final int CYCLES_PER_TICK = 20_000; // ~1.023 MHz * 20ms, approximately
    private static final long TICK_NANOS = FRAME_INTERVAL_MS * 1_000_000L; // fallback pacing only: no audio device
    private static final long PAD_POLL_INTERVAL_MS = 10; // 100 Hz: far faster than any game reads a paddle
    private static final long SHUTDOWN_JOIN_MS = 300; // longest the exit hook waits for the loop to stop
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

        SlotCard[] slots;
        String configFile = cli.get("config");
        if (configFile != null) {
            try {
                slots = SlotCardLoader.load(Path.of(configFile), classLoader);
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                System.err.println("Could not load slot configuration: " + e.getMessage());
                System.exit(1);
                return;
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

        // Runs on both a normal EXIT_ON_CLOSE-triggered exit and a
        // SIGINT/SIGTERM (e.g. Ctrl+C from a terminal) -- shutdown hooks
        // cover either path, unlike relying on JFrame's own close
        // handling alone, which only fires for the window-close case.
        // The pad poller stops first so it stops feeding a machine that is
        // about to go away, then the loop; closing the audio line out from
        // under a thread that is blocked writing to it is not something to
        // rely on.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            padPoller.stop(SHUTDOWN_JOIN_MS);
            loop.stop(SHUTDOWN_JOIN_MS);
            bus.speakerOutput().close();
        }));

        JFrame frame = new JFrame("ReplicApple2Plus");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(screen);
        frame.setResizable(false);

        if (disk != null) {
            JMenuBar menuBar = new JMenuBar();
            menuBar.add(DiskMenu.build(disk, frame, loop));
            frame.setJMenuBar(menuBar);
        }

        KeyboardInputListener keyboardInput = new KeyboardInputListener(bus.keyboardRegister(), loop);
        frame.addKeyListener(keyboardInput);
        frame.setFocusTraversalKeysEnabled(false); // don't let Tab escape focus -- real software may want it

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.requestFocusInWindow();

        if (disk != null && !disk.removableDrives().get(0).isPresent()) {
            promptForBootDisk(disk, frame);
        }

        loop.start();
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
