package com.nordstrom.emulator;

import com.nordstrom.emulator.cpu.Cpu6502;

import javax.swing.JToolBar;
import javax.swing.JButton;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executor;

/**
 * Builds the toolbar holding the machine-level RESET and REBOOT
 * controls.
 * <p>
 * RESET is deliberately NOT a keyboard shortcut. On real Apple II/II+
 * hardware, the RESET key is wired directly to the 6502's hardware
 * RESET pin, bypassing the keyboard encoder entirely -- it never
 * touches the ASCII-producing path every other key uses. Modeling it
 * as a {@link KeyboardMapper} special case would be the wrong shape for
 * what it actually is; this toolbar button goes straight to
 * {@link Cpu6502#raiseReset}/{@link Cpu6502#lowerReset} instead, with no
 * dependency on or interaction with the keyboard path at all. This
 * project's own scope also only calls for the toolbar control, not a
 * keyboard-triggered RESET.
 * <p>
 * Pressing and releasing the button maps directly onto
 * {@code raiseReset()}/{@code lowerReset()} -- not a single click
 * action -- because that pair is not an arbitrary API split: real
 * RESET is a level-triggered line, the CPU freezes for as long as it's
 * held low, and the actual reset sequence fires only on release. A
 * plain {@code ActionListener} (which fires once per completed click)
 * cannot express that; a {@link MouseAdapter} on
 * {@code mousePressed}/{@code mouseReleased} can, and does here.
 * <p>
 * REBOOT, unlike RESET, has no real hardware line behind it -- a power
 * switch is a single discrete event, not something with a meaningful
 * "hold" duration -- so it's a plain click action instead. What it
 * actually does (tearing down and rebuilding the whole machine while
 * preserving currently-inserted disk media) lives in {@code Apple2Plus}
 * itself, not here; this class only wires the button to whatever
 * {@code Runnable} it's given.
 */
final class ToolbarControls {

    private ToolbarControls() {}

    /**
     * Builds the toolbar, wired to {@code cpu}'s reset line and to
     * {@code onReboot}.
     *
     * @param cpu the CPU this toolbar's RESET button controls
     * @param resetCards tells the slot cards RESET was asserted; run on the
     *                   emulation thread together with the CPU's raiseReset()
     * @param emulationThread runs each raise/lower on the emulation thread --
     *                        mouse events arrive on the Swing event thread, and
     *                        the reset line must not be touched concurrently with
     *                        a running tick
     * @param onReboot runs on the Swing event thread when REBOOT is clicked --
     *                 unlike RESET, rebuilding the machine is not something that
     *                 can simply be posted onto the (about to be replaced)
     *                 emulation thread, so this is a plain callback the caller
     *                 is responsible for running safely
     * @return the built toolbar, ready to add to a {@code JFrame}
     */
    static JToolBar build(Cpu6502 cpu, Runnable resetCards, Executor emulationThread, Runnable onReboot) {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        JButton resetButton = new JButton("Reset");
        resetButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                emulationThread.execute(() -> {
                    cpu.raiseReset();
                    resetCards.run(); // the slot connector carries RESET to every card
                });
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                emulationThread.execute(cpu::lowerReset);
            }
        });
        toolbar.add(resetButton);

        JButton rebootButton = new JButton("Reboot");
        rebootButton.addActionListener(e -> onReboot.run());
        toolbar.add(rebootButton);

        return toolbar;
    }
}

