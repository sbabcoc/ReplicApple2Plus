package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.system.RemovableMediaDrive;
import com.nordstrom.emulator.system.SlotCard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * A Disk II floppy disk controller card. Named {@code Disk2Controller},
 * not {@code DiskIIController} -- Roman numerals are more likely to be
 * misread than Arabic ones, the same reasoning behind this project's own
 * name, {@code ReplicApple2Plus}.
 * <p>
 * Genuinely complete now, not a partial slice: the boot ROM
 * ({@link DiskBootRom}, verified against two independent sources), all
 * 16 soft switches (phase stepper, motor, drive select, Q6/Q7 mode
 * latches), real phase-to-track-position stepping (confirmed against
 * two independent sources on the actual electromagnet sequencing), a
 * real WOZ disk image loader ({@link WozDiskImage}), and the actual
 * Logic State Sequencer ({@link Disk2LogicSequencer}) reading real
 * bitstream data through Q6/Q7 -- all wired together and verified
 * end-to-end, not just individually.
 * <p>
 * {@link #tick} is what actually drives disk reading -- it needs to be
 * registered with {@code SystemClock.addCycleListener} by whatever
 * assembles the real machine, the same way {@code PaddleTimers} and
 * {@code VideoScanner} already are, reached via
 * {@code MotherboardBus.slotCard(int)} and a cast (this class
 * deliberately isn't referenced generically through {@code SlotCard}
 * itself -- see {@code MotherboardBus.slotCard}'s own Javadoc for why).
 * Without that registration, this controller's switches and boot ROM
 * still work, but no bits ever actually move: the LSS simply never
 * advances.
 * <p>
 * Real hardware detail worth being explicit about: EVERY access to
 * $C0n0-$C0nF, read or write, is significant purely as an address-decode
 * event -- software commonly does {@code LDA $C0n8} or {@code BIT $C0n9}
 * to flip a switch, discarding whatever byte comes back. For offsets
 * 0x0-0xB (phase/motor/drive-select), real software never inspects the
 * returned value, so this returns a harmless 0 on read after applying
 * the state change -- that's a deliberate, narrow exception to
 * "don't return unverified data," justified specifically because no
 * real program ever looks at it. Offsets 0xC-0xF are different: their
 * return value is the actual data latch, via {@link Disk2LogicSequencer#latch}.
 * <p>
 * Two named, deliberate simplifications remain, both documented at
 * their actual source rather than here: {@link Drive#currentTrackStream}
 * resets to bit 0 on every track change rather than preserving relative
 * rotational position the way the WOZ spec recommends (a real gap for a
 * small number of copy-protection schemes, not ordinary reading), and
 * {@link #tick} doesn't model a documented DOS 3.2 {@code INIT}
 * sub-instruction timing quirk that this project's instruction-boundary
 * cycle granularity can't represent.
 */
public final class Disk2Controller implements SlotCard {

    /** LSS ticks per bit-cell (real hardware: 8 ticks divide each 4-CPU-cycle bit cell -- confirmed independently against two sources). */
    private static final int LSS_TICKS_PER_BIT_CELL = 8;
    /** LSS ticks per CPU cycle (real hardware: the LSS runs at 2x the CPU clock rate). */
    private static final int LSS_TICKS_PER_CPU_CYCLE = 2;

    private final boolean[] phaseOn = new boolean[4];
    private boolean motorOn;
    private int selectedDrive; // 0 or 1
    private boolean q6;
    private boolean q7;
    private final Disk2LogicSequencer logicSequencer = new Disk2LogicSequencer();
    private int lssPhaseCounter; // 0 to LSS_TICKS_PER_BIT_CELL-1, cycling

    private final DiskBootRom bootRom = new DiskBootRom();
    private final Drive[] drives = { new Drive(), new Drive() };

    @Override
    public String getShortName() {
        return "disk2";
    }

    @Override
    public Set<String> getSupportedParameters() {
        return Set.of("drive1", "drive2");
    }

    @Override
    public void configure(Properties props) {
        loadInitial(props.getProperty("drive1"), drives[0]);
        loadInitial(props.getProperty("drive2"), drives[1]);
    }

    private void loadInitial(String path, Drive drive) {
        if (path == null) {
            return;
        }
        try {
            drive.insert(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load initial disk image \"" + path + "\"", e);
        }
    }

    @Override
    public int readIoSwitch(int offset) {
        applySwitch(offset);
        if (offset >= 0xC) {
            return logicSequencer.latch();
        }
        return 0; // harmless -- real software never inspects this for offsets 0x0-0xB, see class Javadoc
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        applySwitch(offset);
        // Real software loads the write-data register via a write while in (or entering) load
        // mode (Q6=1,Q7=1) -- conventionally STA $C08D,X specifically. Setting this unconditionally
        // on every write is safe: the LSS only ever reads it during its own LD action, which only
        // fires in that exact mode, so a write here while not in load mode has no observable effect.
        logicSequencer.setWriteDataRegister(value);
    }

    /**
     * Advances the Logic State Sequencer by the real hardware ratio: 2
     * LSS ticks per CPU cycle elapsed, sampling one new bit from the
     * selected drive's current track every 8 LSS ticks (matching the
     * real 4-CPU-cycle-per-bit data rate) -- confirmed independently
     * against two sources describing the real 8-phase, 4-cycle-bit-cell
     * clock. Does nothing at all while the motor is off, matching real
     * hardware: no bits are being read off a disk that isn't spinning.
     * <p>
     * Deliberately does not model the documented DOS 3.2 {@code INIT}
     * sub-instruction timing quirk (Sather, "Understanding the Apple
     * II," p.9-22) where the LSS completes an extra cycle between the
     * CPU's address-bus setup and its actual read within a single
     * instruction -- this project's architecture ticks at
     * instruction-completion granularity, not sub-instruction, and
     * modeling that specific case would need the same kind of
     * fundamental restructuring {@code SystemClock}'s own Javadoc
     * already declines for video/CPU interleaving, for a narrower,
     * older-software-specific benefit.
     *
     * @param cycles CPU cycles elapsed since the last tick
     */
    public void tick(int cycles) {
        if (!motorOn) {
            return;
        }
        Drive drive = drives[selectedDrive];
        TrackBitStream stream = drive.currentTrackStream();
        DiskImage image = drive.diskImage();
        logicSequencer.setWriteProtected(image != null && image.isWriteProtected());

        int totalTicks = cycles * LSS_TICKS_PER_CPU_CYCLE;
        for (int i = 0; i < totalTicks; i++) {
            int pulseBit = 0;
            if (lssPhaseCounter == 0 && stream != null) {
                pulseBit = stream.nextBit();
            }
            logicSequencer.tick(pulseBit);
            lssPhaseCounter = (lssPhaseCounter + 1) % LSS_TICKS_PER_BIT_CELL;
        }
    }

    private void applySwitch(int offset) {
        switch (offset) {
            case 0x0 -> turnOffPhase(0);
            case 0x1 -> phaseOn[0] = true;
            case 0x2 -> turnOffPhase(1);
            case 0x3 -> phaseOn[1] = true;
            case 0x4 -> turnOffPhase(2);
            case 0x5 -> phaseOn[2] = true;
            case 0x6 -> turnOffPhase(3);
            case 0x7 -> phaseOn[3] = true;
            case 0x8 -> motorOn = false;
            case 0x9 -> motorOn = true;
            case 0xA -> selectedDrive = 0;
            case 0xB -> selectedDrive = 1;
            case 0xC -> { q6 = false; logicSequencer.setQ6(false); }
            case 0xD -> { q6 = true; logicSequencer.setQ6(true); }
            case 0xE -> { q7 = false; logicSequencer.setQ7(false); }
            case 0xF -> { q7 = true; logicSequencer.setQ7(true); }
            default -> throw new IllegalArgumentException("offset " + offset + " is outside 0-15");
        }
    }

    /**
     * Turns off phase {@code n}, stepping the currently-selected drive's
     * head if this is a genuine on-to-off transition with exactly one
     * adjacent phase currently on -- the real Disk II stepper mechanism
     * (confirmed against two independent sources): a step only occurs
     * when the phase being turned off had been on, and only one of its
     * two neighbors is energized. The next (clockwise) neighbor being on
     * steps inward (higher track numbers); the previous
     * (counter-clockwise) neighbor being on steps outward (toward track
     * 0). Both neighbors on, or neither, means no step -- the head
     * doesn't move on every switch access, only on a real, unambiguous
     * transition.
     * <p>
     * Each such clean transition moves the head by 2 quarter-tracks, not
     * 1: real Apple documentation ("Beneath Apple DOS") describes the
     * disk arm as positionable over 70 "phases" across 35 tracks -- 2
     * phases per track -- with "two phases of the stepper motor... must
     * be cycled" to move one full track. A real Apple "phase" is
     * therefore 2 quarter-tracks (this project's own indexing unit,
     * matching the WOZ format's 0-159 range for representing disk data
     * positions), and standard DOS's own phase-stepping code only ever
     * issues one clean transition per "phase" of desired movement --
     * confirmed directly against a real Virtual ][ trace of this exact
     * disk's boot sequence, where a fixed seek target produced exactly
     * double this project's own resulting head travel before this fix.
     *
     * @param n the phase (0-3) being turned off
     */
    private void turnOffPhase(int n) {
        if (phaseOn[n]) {
            boolean nextOn = phaseOn[(n + 1) % 4];
            boolean prevOn = phaseOn[(n + 3) % 4];
            phaseOn[n] = false;
            if (nextOn && !prevOn) {
                drives[selectedDrive].step(2);
            } else if (prevOn && !nextOn) {
                drives[selectedDrive].step(-2);
            }
        }
    }


    @Override
    public int readRom(int offset) {
        return bootRom.read(offset);
    }

    @Override
    public List<RemovableMediaDrive> removableDrives() {
        return List.of(drives[0], drives[1]);
    }

    /** Package-visible for tests -- direct access to drive {@code n} (0 or 1) as a concrete {@link Drive}. */
    Drive drive(int n) {
        return drives[n];
    }

    /** Package-visible for tests -- true if the phase-{@code n} stepper magnet is currently energized. */
    boolean isPhaseOn(int n) {
        return phaseOn[n];
    }

    /** Package-visible for tests. */
    boolean isMotorOn() {
        return motorOn;
    }

    /** Package-visible for tests. */
    int selectedDrive() {
        return selectedDrive;
    }

    /**
     * One of this controller's two drives. Tracks which image is
     * currently loaded and makes its track data available to the LSS
     * via {@link #diskImage}. {@code insert} picks {@link WozDiskImage}
     * or {@link DskDiskImage} by file extension ({@code .dsk}/{@code
     * .do} versus everything else) -- callers never need to know or
     * care which one actually ends up loaded.
     */
    static final class Drive implements RemovableMediaDrive {

        /** Real WOZ range: 0-159 quarter-tracks (up to 40 tracks). Clamped, matching the real head hitting a mechanical stop. */
        private static final int MAX_QUARTER_TRACK = 159;

        private Path currentImage;
        private DiskImage diskImage;
        private int quarterTrack;
        private TrackBitStream currentTrackStream;
        private int streamedQuarterTrack = -1; // sentinel: no stream cached yet

        /**
         * Moves the head by one quarter-track, clamped to the real
         * 0-159 range -- real hardware has no position sensor and
         * simply stops moving (and makes noise) if software keeps
         * stepping past either end.
         *
         * @param direction +1 (inward, higher track numbers) or -1 (outward, toward track 0)
         */
        void step(int direction) {
            quarterTrack = Math.max(0, Math.min(MAX_QUARTER_TRACK, quarterTrack + direction));
        }

        /** @return the current head position, 0-159 quarter-tracks */
        int quarterTrack() {
            return quarterTrack;
        }

        /**
         * The bit stream for whichever track the head currently sits
         * over. Cached and only refreshed when the head has actually
         * moved to a different quarter-track since the last call --
         * calling {@link WozDiskImage#trackAt} fresh every tick would
         * silently reset the read position to 0 constantly, since each
         * call returns a brand new {@link TrackBitStream}.
         * <p>
         * Deliberately simplified relative to the WOZ spec's own
         * recommendation: real hardware (and a fully faithful emulator)
         * preserves the bitstream's relative rotational position when
         * changing tracks, scaled by the new track's length, so the
         * head appears to still be over roughly the same physical area
         * of the disk. This just starts over at bit 0 on every track
         * change instead. That's a real gap for the small number of
         * copy-protection schemes that specifically check cross-track
         * bit alignment -- not a concern for ordinary disk reading.
         *
         * @return the current track's bit stream, or {@code null} if no disk is loaded
         */
        TrackBitStream currentTrackStream() {
            if (diskImage == null) {
                return null;
            }
            if (currentTrackStream == null || streamedQuarterTrack != quarterTrack) {
                currentTrackStream = diskImage.trackAt(quarterTrack);
                streamedQuarterTrack = quarterTrack;
            }
            return currentTrackStream;
        }


        @Override
        public void insert(Path imagePath) throws IOException {
            if (!Files.exists(imagePath)) {
                throw new IOException("Disk image not found: " + imagePath);
            }
            String name = imagePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".dsk") || name.endsWith(".do")) {
                diskImage = DskDiskImage.load(imagePath);
            } else {
                diskImage = WozDiskImage.load(imagePath);
            }
            currentImage = imagePath;
            currentTrackStream = null;
            streamedQuarterTrack = -1; // force a fresh stream even if the head hasn't moved
        }

        @Override
        public void eject() {
            currentImage = null;
            diskImage = null;
        }

        @Override
        public boolean isPresent() {
            return currentImage != null;
        }

        /** Package-visible for tests, and for the LSS to consume. */
        DiskImage diskImage() {
            return diskImage;
        }

        @Override
        public Optional<Path> currentImagePath() {
            return Optional.ofNullable(currentImage);
        }
    }
}
