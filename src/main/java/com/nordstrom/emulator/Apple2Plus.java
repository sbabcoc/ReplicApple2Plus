package com.nordstrom.emulator;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The application's entry point. Assembles a real, running machine: no
 * slot cards yet (so no disk boot support), which means the real
 * `$FFFC` Autostart ROM path boots straight to Applesoft/the Monitor
 * prompt -- exactly the target this class was building toward.
 * <p>
 * Cycle pacing: this class, not {@link SystemClock}, decides how fast
 * to run and how often to repaint -- {@link SystemClock} deliberately
 * has no opinion about real-time pacing at all (see its own Javadoc).
 * {@link #CYCLES_PER_TICK} is a rough approximation of the real
 * ~1.023 MHz clock rate at this timer's interval, not an exact,
 * calibrated match -- real-time accuracy to that degree isn't this
 * project's current goal.
 * <p>
 * A gap hit during interactive use (an address range this project
 * hasn't modeled yet, say) is reported and stops emulation cleanly
 * rather than crashing the Swing event thread with an unhandled
 * exception -- the window stays open and showing whatever was last
 * drawn, rather than vanishing.
 */
public final class Apple2Plus {

    private static final int FRAME_INTERVAL_MS = 20; // ~50 Hz
    private static final int CYCLES_PER_TICK = 20_000; // ~1.023 MHz * 20ms, approximately
    private static final int FLASH_TOGGLE_EVERY_N_TICKS = 15; // roughly twice a second at 50 Hz

    /**
     * Application entry point.
     *
     * @param args command-line arguments (currently unused)
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Apple2Plus::createAndRun);
    }

    private static void createAndRun() {
        SlotCard[] slots = new SlotCard[8]; // no cards -- no disk boot support yet
        MotherboardBus bus = new MotherboardBus(slots);
        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC); // the real, unmodified Autostart reset vector
        SystemClock clock = new SystemClock(cpu);
        clock.addCycleListener(bus.videoScanner()::tick);

        ScreenPanel screen = new ScreenPanel(bus);

        JFrame frame = new JFrame("ReplicApple2Plus");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(screen);
        frame.setResizable(false);

        KeyboardInputListener keyboardInput = new KeyboardInputListener(bus.keyboardRegister());
        frame.addKeyListener(keyboardInput);
        frame.setFocusTraversalKeysEnabled(false); // don't let Tab escape focus -- real software may want it

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.requestFocusInWindow();

        int[] ticksSinceFlash = {0};
        Timer timer = new Timer(FRAME_INTERVAL_MS, event -> {
            try {
                for (int cycles = 0; cycles < CYCLES_PER_TICK; ) {
                    cycles += clock.step();
                }
            } catch (RuntimeException e) {
                System.err.println("Emulation stopped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ((Timer) event.getSource()).stop();
                return;
            }

            if (++ticksSinceFlash[0] >= FLASH_TOGGLE_EVERY_N_TICKS) {
                screen.toggleFlash();
                ticksSinceFlash[0] = 0;
            }
            screen.repaint();
        });
        timer.start();
    }

    private Apple2Plus() {}
}
