package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the exact class of bug found here: a subsystem whose
 * {@code tick()} was never wired into the clock's cycle listeners at
 * startup, so it silently never advanced. This is genuinely hard to
 * catch with a behavioral test, since {@code Apple2Plus.main()} builds
 * real Swing components and starts a real thread; there is no seam to
 * intercept the wiring itself without a larger refactor.
 * <p>
 * The specific failure: {@code paddleTimers()::tick} was missing from
 * {@code Apple2Plus.createAndRun}, so a paddle's countdown, once
 * started, never counted down again. Every subsequent
 * {@code setPosition} silently had no effect, because
 * {@code PaddleTimers.trigger()} only reloads a channel whose countdown
 * has already reached zero -- which it never did. The result: real
 * game controller input worked at every layer (confirmed directly, with
 * diagnostics, all the way to the call into {@code BusInputSink}) while
 * {@code PDL()} in a running program stayed pegged at 255 regardless of
 * stick position. Every other cycle-driven subsystem (video, scanline
 * modes, speaker, disk) was correctly wired; only this one line was
 * missing, and it produced no compile error, no exception, and no
 * visible symptom anywhere except this one specific, easy-to-miss
 * behavior.
 * <p>
 * This checks the actual source text for the wiring calls, rather than
 * the running behavior, because that behavior isn't reachable from a
 * unit test here. A source-text check is unusual for this codebase, and
 * it will need updating if this wiring is ever legitimately restructured
 * -- but the alternative is leaving this specific mistake free to
 * reappear silently, exactly as it did.
 */
class Apple2PlusWiringTest {

    private static String sourceText() throws IOException {
        // Gradle's Test task defaults to the project root as its working
        // directory, so this resolves directly in the normal case. Falls
        // back to searching upward in case that default is ever overridden,
        // rather than failing outright on an assumption about cwd.
        Path relative = Path.of("src/main/java/com/nordstrom/emulator/Apple2Plus.java");
        Path found = Files.isRegularFile(relative) ? relative : searchUpward(relative);
        assertTrue(found != null, "expected to find Apple2Plus.java at " + relative + " (relative to the "
            + "working directory) or from some ancestor of it -- if the source layout changed, update this "
            + "test's path rather than remove the check");
        return Files.readString(found);
    }

    private static Path searchUpward(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void everyCycleDrivenSubsystemIsWiredIntoTheClock() throws IOException {
        String source = sourceText();
        // Each of these must appear as a clock.addCycleListener(...) target.
        // paddleTimers is the one that was actually missing; the others are
        // included so this test also catches the same mistake happening to
        // any of them in the future.
        String[] requiredListeners = {
            "bus.videoScanner()::tick",
            "bus.scanlineModes()::tick",
            "bus.speakerOutput()::tick",
            "bus.paddleTimers()::tick",
        };
        for (String listener : requiredListeners) {
            assertTrue(source.contains("clock.addCycleListener(" + listener + ")"),
                "Apple2Plus.java must wire " + listener + " into clock.addCycleListener(...) -- "
                    + "without it, that subsystem's tick() is never called and it silently never advances. "
                    + "This is exactly the bug that left PDL() pegged at 255 despite every other layer of "
                    + "gamepad input working correctly.");
        }
    }
}
