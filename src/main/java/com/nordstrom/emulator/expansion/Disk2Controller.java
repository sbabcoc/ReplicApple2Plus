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
 * to flip a switch, discarding whatever byte comes back. For most offsets
 * 0x0-0xB (phase/motor/drive-select), software indeed discards the
 * returned value -- but not universally: real, verified DOS 3.3 RWTS code
 * has at least one path ({@code LDA $C08A,X} / {@code LDA $C08B,X}) that
 * genuinely reads the result and uses it (as an {@code $BA00} delay-loop
 * count). On real hardware these offsets don't drive the data bus, so a
 * read shows the floating bus -- whatever the video circuitry last
 * fetched, not a fixed value; this is wired in via
 * {@link #setFloatingBusSupplier} (see {@link #readIoSwitch}'s own
 * Javadoc for the confirmed, measured reason this matters, and for an
 * earlier revert of this exact fix that turned out to be unwarranted).
 * Offsets 0xC-0xF are different: their return value is the actual data
 * latch, via {@link Disk2LogicSequencer#latch}.
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
    /**
     * Real hardware doesn't cut motor power the instant software touches
     * the motor-off switch: the controller card has a 556 dual-timer chip
     * whose one-shot half keeps the motor spinning for a bounded grace
     * period after deselection, specifically so quick, repeated accesses
     * (retries, or successive sector loads during boot) don't pay a real
     * spin-down/spin-up cost each time. This value is 1.1 * R * C for that
     * one-shot (the standard 555/556 monostable formula), using R = 47k
     * ohms and C = 22uF read directly off the real Disk II controller
     * schematic (independently verified against several sources; see
     * DOS33-BOOT-INVESTIGATION.md UPDATE 48), converted to CPU cycles at
     * Sather's documented primary 6502 clock rate (~1.0227 MHz, see
     * "Understanding the Apple II" Chapter 3). This lands close enough to
     * DOS's own natural per-attempt cadence (independently measured in
     * this project's own boot trace at roughly 1.0-1.2 seconds between
     * successive attempts) that whether the motor is still spinning when
     * the next attempt begins is a genuine, hardware-timing-dependent
     * race on real hardware -- not a fixed outcome either way.
     */
    private static final long MOTOR_OFF_DELAY_CYCLES = 1_163_250L;

    private final boolean[] phaseOn = new boolean[4];
    private boolean motorOn;
    /**
     * Cycles remaining before a pending motor-off actually takes effect;
     * 0 means no shutoff is pending. Only meaningful while {@link
     * #motorOn} is true -- see {@link #MOTOR_OFF_DELAY_CYCLES}.
     */
    private long motorOffCountdownCycles;
    private int selectedDrive; // 0 or 1
    private boolean q6;
    private boolean q7;
    /**
     * The selected drive's write-protect sensor, as last read -- see
     * {@link #senseWriteProtect}. Cached rather than read on every tick:
     * for a DSK (and an unprotected WOZ) the state comes from the host
     * file's permission, a filesystem call -- far too costly to make after
     * every CPU instruction, especially where system calls are expensive
     * (Termux/PRoot intercepts each one).
     */
    private boolean writeProtected;
    private final Disk2LogicSequencer logicSequencer = new Disk2LogicSequencer();
    private int lssPhaseCounter; // 0 to LSS_TICKS_PER_BIT_CELL-1, cycling
    /** Current level of the drive's write line -- see {@link Disk2LogicSequencer#writeSignal}. */
    private boolean writeLineActive;
    /** Whether the write line changed level during the bit cell now in progress (i.e. that cell gets a 1). */
    private boolean fluxInCurrentCell;

    private final DiskBootRom bootRom = new DiskBootRom();
    private final Drive[] drives = { new Drive(1), new Drive(2) };
    {
        // Each drive knows its sibling so insert() can refuse an image the
        // other drive already holds -- see Drive#insert.
        drives[0].sibling = drives[1];
        drives[1].sibling = drives[0];
    }

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

    private java.util.function.IntSupplier floatingBusSupplier;

    /**
     * Wires in the real floating-bus read this card needs for soft-switch
     * offsets that don't drive the data bus themselves (see
     * {@link #readIoSwitch}'s Javadoc for why this exists). Left unwired
     * (e.g. in isolated unit tests), those offsets simply read back as 0.
     *
     * @param floatingBusSupplier supplies the current floating-bus byte
     */
    public void setFloatingBusSupplier(java.util.function.IntSupplier floatingBusSupplier) {
        this.floatingBusSupplier = floatingBusSupplier;
    }

    @Override
    public int readIoSwitch(int offset) {
        applySwitch(offset);
        if (offset >= 0xC) {
            return logicSequencer.latch();
        }
        // Real hardware shows the floating bus here (whatever the video
        // circuitry last fetched) rather than a fixed value. At least one
        // real, verified DOS 3.3 RWTS path (LDA $C08A,X / $C08B,X, part of
        // the seek-retry preamble) reads this result and uses it directly
        // as an $BA00 delay-loop count -- a fixed 0 makes that count wrap
        // to its 8-bit maximum (256 iterations) on every touch, confirmed
        // (via a real, breakpoint-based trace against Virtual ][ on
        // identical ROM/disk images) to cost roughly 1,225x real hardware's
        // own cost at this exact code path (DOS33-BOOT-INVESTIGATION.md
        // UPDATE 46). A real floating-bus value doesn't fully close that
        // gap on its own -- this emulator's own video-scanner timing still
        // doesn't land on the same byte real hardware's video circuitry
        // would at this instant, a separate, still-open question -- but it
        // is confirmed correct behavior and a real, measurable improvement
        // over the fixed 0 (the wraparound no longer fires on every single
        // touch). An earlier attempt at this exact fix was reverted after
        // an apparent real-world regression report (UPDATE 44), but that
        // regression was later shown to be unrelated to this change --
        // reverting made no actual difference to measured boot time
        // (UPDATE 45) -- so there's no known reason not to keep this.
        return floatingBusSupplier != null ? floatingBusSupplier.getAsInt() : 0;
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
        if (motorOn && motorOffCountdownCycles > 0) {
            motorOffCountdownCycles -= cycles;
            if (motorOffCountdownCycles <= 0) {
                motorOffCountdownCycles = 0;
                motorOn = false;
            }
        }
        if (!motorOn) {
            return;
        }
        Drive drive = drives[selectedDrive];
        TrackBitStream stream = drive.currentTrackStream();
        DiskImage image = drive.diskImage();

        int totalTicks = cycles * LSS_TICKS_PER_CPU_CYCLE;
        for (int i = 0; i < totalTicks; i++) {
            int pulseBit = 0;
            if (lssPhaseCounter == 0) {
                // Bit-cell boundary: the disk advances exactly one bit
                // cell here, in every mode -- it keeps spinning under the
                // head regardless of what the controller is doing. In
                // read mode the cell's bit feeds the LSS; in either Q7=1
                // mode the cell just ended receives a 1 if the write line
                // changed level during it, else a 0 (see
                // Disk2LogicSequencer#writeSignal). Tying the advance or the
                // written bit to LSS shift actions instead would skip the
                // one cell per byte where the LSS performs LD, putting
                // every byte on the disk as 7 bits.
                if (stream != null) {
                    if (!q7) {
                        pulseBit = stream.nextBit();
                    } else if (writeProtected) {
                        // Real hardware's write-protect notch sensor lives in
                        // the drive, not the controller card: the LSS still
                        // drives its write line, the drive just ignores it.
                        // The head still moves over the disk as usual, so
                        // position still advances.
                        stream.nextBit();
                    } else {
                        stream.writeBit(fluxInCurrentCell ? 1 : 0);
                        image.markDirty(); // exactly where a real write happens -- see DiskImage.markDirty's own Javadoc
                    }
                }
                fluxInCurrentCell = false;
            }
            logicSequencer.tick(pulseBit);
            if (q7) {
                // Mirrors MAME's wozfdc: while in write mode, any
                // difference between the sequencer's write signal and the
                // write line's current level flips the line -- one flux
                // transition. Outside write mode the line simply holds.
                boolean writeSignal = logicSequencer.writeSignal() != 0;
                if (writeSignal != writeLineActive) {
                    writeLineActive = writeSignal;
                    fluxInCurrentCell = true;
                }
            }
            lssPhaseCounter = (lssPhaseCounter + 1) % LSS_TICKS_PER_BIT_CELL;
        }
    }

    /**
     * Reads the selected drive's write-protect sensor into {@link #writeProtected}.
     * Called at the moments software can actually observe it -- entering
     * sense mode (Q6 on, Q7 off) to test the notch, entering write mode
     * (Q7 on), where a protected disk refuses the write, and selecting a
     * drive -- rather than on every tick, so ordinary reading never touches
     * the host filesystem.
     */
    private void senseWriteProtect() {
        DiskImage image = drives[selectedDrive].diskImage();
        writeProtected = image != null && image.isWriteProtected();
        logicSequencer.setWriteProtected(writeProtected);
    }

    private void selectDrive(int drive) {
        if (selectedDrive != drive) {
            selectedDrive = drive;
            senseWriteProtect();
        }
    }

    private void applySwitch(int offset) {
        switch (offset) {
            case 0x0 -> turnOffPhase(0);
            case 0x1 -> turnOnPhase(0);
            case 0x2 -> turnOffPhase(1);
            case 0x3 -> turnOnPhase(1);
            case 0x4 -> turnOffPhase(2);
            case 0x5 -> turnOnPhase(2);
            case 0x6 -> turnOffPhase(3);
            case 0x7 -> turnOnPhase(3);
            case 0x8 -> {
                // Real hardware doesn't cut power immediately -- it starts
                // (or restarts) the controller card's one-shot grace period
                // instead; see MOTOR_OFF_DELAY_CYCLES. The motor stays
                // reported on, and the LSS keeps advancing, until tick()
                // observes this countdown actually reach zero.
                if (motorOn) {
                    motorOffCountdownCycles = MOTOR_OFF_DELAY_CYCLES;
                }
            }
            case 0x9 -> {
                // Real hardware: no equivalent delay on power-up -- confirmed
                // via this project's own soft-switch code (a direct,
                // unconditional flip), matching every real trace this
                // investigation captured.
                motorOn = true;
                motorOffCountdownCycles = 0;
            }
            case 0xA -> selectDrive(0);
            case 0xB -> selectDrive(1);
            case 0xC -> { q6 = false; logicSequencer.setQ6(false); }
            case 0xD -> {
                boolean enteringSense = !q6 && !q7;
                q6 = true;
                logicSequencer.setQ6(true);
                if (enteringSense) {
                    senseWriteProtect();
                }
            }
            case 0xE -> {
                boolean enteringSense = q7 && q6;
                q7 = false;
                logicSequencer.setQ7(false);
                if (enteringSense) {
                    senseWriteProtect();
                }
            }
            case 0xF -> {
                boolean enteringWrite = !q7;
                q7 = true;
                logicSequencer.setQ7(true);
                if (enteringWrite) {
                    senseWriteProtect();
                }
            }
            default -> throw new IllegalArgumentException("offset " + offset + " is outside 0-15");
        }
    }

    /**
     * Turns off phase {@code n} -- see {@link #settleHead}.
     *
     * @param n the phase (0-3) being turned off
     */
    private void turnOffPhase(int n) {
        phaseOn[n] = false;
        settleHead();
    }

    /**
     * Turns on phase {@code n} -- see {@link #settleHead}.
     *
     * @param n the phase (0-3) being turned on
     */
    private void turnOnPhase(int n) {
        phaseOn[n] = true;
        settleHead();
    }

    /**
     * Moves the selected drive's head the way the stepper magnets pull it
     * from where it actually is. The head sits over one of the four phase
     * magnets (half-track {@code h} is over magnet {@code h mod 4}). If that
     * magnet is off and exactly one of its two neighbors is on, the head is
     * drawn half a track -- 2 quarter-tracks -- toward the energized
     * neighbor. Otherwise it stays: held by its own magnet, balanced between
     * two energized neighbors, or with nothing nearby pulling at all.
     * <p>
     * Deciding from the head's real position, not from a separately
     * remembered "last phase", keeps the two from ever disagreeing. That
     * covers every pattern software uses: DOS's overlapped seeks (the new
     * phase on while the old is still on -- the head moves when the old
     * one goes off), the boot ROM's non-overlapped recalibration, and
     * ProDOS's seeks, which a remembered-phase model could step the wrong
     * way after a different program had last moved the head.
     */
    private void settleHead() {
        Drive drive = drives[selectedDrive];
        int phase = (drive.quarterTrack() / 2) % 4;
        if (phaseOn[phase]) {
            return;
        }
        boolean nextOn = phaseOn[(phase + 1) % 4];
        boolean prevOn = phaseOn[(phase + 3) % 4];
        if (nextOn && !prevOn) {
            drive.step(2);
        } else if (prevOn && !nextOn) {
            drive.step(-2);
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

    /**
     * Persists any unpersisted writes on whichever of this controller's
     * two drives currently has a disk loaded -- for callers (application
     * exit, Reboot) that need to be sure nothing is left unsaved at a
     * point where the normal triggers (a track change, an eject) won't
     * necessarily have already run. A no-op per drive if nothing is
     * actually dirty, or if no disk is loaded there at all.
     *
     * @throws IOException if either drive's writes exist but can't be persisted
     */
    public void persist() throws IOException {
        for (Drive drive : drives) {
            DiskImage image = drive.diskImage();
            if (image != null) {
                image.persist();
            }
        }
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
     * .do}/{@code .po} versus everything else) -- callers never need to know or
     * care which one actually ends up loaded.
     */
    static final class Drive implements RemovableMediaDrive {

        /** Real WOZ range: 0-159 quarter-tracks (up to 40 tracks). Clamped, matching the real head hitting a mechanical stop. */
        private static final int MAX_QUARTER_TRACK = 159;

        /** 1 or 2, as software and the user see it -- for error messages. */
        private final int number;
        /** The controller's other drive -- set once, right after construction. */
        private Drive sibling;
        private Path currentImage;
        private DiskImage diskImage;

        private int quarterTrack;
        private TrackBitStream currentTrackStream;
        private int streamedQuarterTrack = -1; // sentinel: no stream cached yet

        Drive(int number) {
            this.number = number;
        }

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
                // About to leave this track -- persist whatever was written
                // to it (or any other track) before fetching the new one.
                // persist() itself is a no-op when nothing is actually
                // dirty, so this costs nothing extra during ordinary
                // reading, which seeks across tracks constantly with no
                // writes involved at all. Caught, not propagated: this
                // runs on the emulation thread's own tick(), which has no
                // checked-exception path to the rest of the application,
                // and the in-memory state (what the rest of this session
                // sees) is already correct regardless of whether this
                // particular file write succeeds.
                if (currentTrackStream != null) {
                    try {
                        diskImage.persist();
                    } catch (IOException e) {
                        System.err.println("Could not persist disk write to " + currentImage + ": " + e.getMessage());
                    }
                }
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
            // A physical diskette can only be in one drive at a time. Beyond
            // realism, two drives holding the same file would each keep
            // their own in-memory DiskImage and persist() over each other,
            // silently losing whichever drive's writes landed first.
            // Files.isSameFile catches the same file reached by a different
            // path (symlinks, hard links, "..", case on a case-insensitive
            // volume), not just identical path strings.
            if (sibling != null && sibling.currentImage != null && isSameFile(imagePath, sibling.currentImage)) {
                throw new IOException("That disk is already in drive " + sibling.number
                    + " -- eject it there first: " + imagePath);
            }
            persistBeforeReplacing(); // before loading, so re-inserting the same file reads the saved writes
            String name = imagePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            DiskImage image = (name.endsWith(".dsk") || name.endsWith(".do") || name.endsWith(".po"))
                ? DskDiskImage.load(imagePath)
                : WozDiskImage.load(imagePath);
            insertLoadedImage(image, imagePath);
        }

        @Override
        public void insertNewBlankDisk(Path path) throws IOException {
            // Deliberately NOT insert(path) after creating the file
            // separately -- that would re-load it from disk via
            // WozDiskImage.load(), which has no way to know any of its
            // tracks are still virgin (see WozDiskImage.createBlank's own
            // Javadoc on that exact limitation), defeating genuine
            // per-read randomization before the disk has even been used
            // once. Keeping createBlank's own returned instance, with its
            // real in-memory virgin tracking intact, is the whole point.
            persistBeforeReplacing();
            // Format by extension, matching insert()'s own dispatch.
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            DiskImage image = (name.endsWith(".dsk") || name.endsWith(".do") || name.endsWith(".po"))
                ? DskDiskImage.createBlank(path)
                : WozDiskImage.createBlank(path);
            insertLoadedImage(image, path);
        }

        /**
         * {@link Files#isSameFile}, treating a sibling image that can no
         * longer be found on the host (deleted or moved since insertion)
         * as a different file rather than an error -- the new image itself
         * was already confirmed to exist.
         */
        private static boolean isSameFile(Path a, Path b) throws IOException {
            try {
                return Files.isSameFile(a, b);
            } catch (java.nio.file.NoSuchFileException e) {
                return false;
            }
        }

        /** Shared by {@link #insert} and {@link #insertNewBlankDisk} once each has its own, already-built {@link DiskImage}. */
        /**
         * Saves the loaded disk's pending writes before something replaces
         * it -- the same guarantee {@link #eject} gives, by the same rule:
         * if saving fails, this throws and the current disk stays in place,
         * keeping the only copy of those writes rather than dropping it.
         */
        private void persistBeforeReplacing() throws IOException {
            if (diskImage != null) {
                diskImage.persist();
            }
        }

        private void insertLoadedImage(DiskImage image, Path imagePath) {
            diskImage = image;
            currentImage = imagePath;
            currentTrackStream = null;
            streamedQuarterTrack = -1; // force a fresh stream even if the head hasn't moved
        }

        @Override
        public void eject() throws IOException {
            if (diskImage != null) {
                // Deliberately lets this propagate, rather than catching
                // and logging the way currentTrackStream()'s own persist()
                // call does: a failure here must NOT silently clear
                // diskImage below, since that would drop the only
                // reference to the in-memory state holding the unpersisted
                // write -- the next insert() of this same path would then
                // load the file fresh from disk, permanently losing a
                // write that still existed only in memory. Not ejecting
                // at all until this succeeds keeps both the reference and
                // the chance to retry.
                diskImage.persist();
            }
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

        @Override
        public boolean isWriteProtected() {
            return diskImage != null && diskImage.isWriteProtected();
        }

        @Override
        public void setWriteProtected(boolean protect) throws IOException {
            if (diskImage == null) {
                throw new IllegalStateException("Cannot set write-protect state: no disk is loaded in this drive");
            }
            diskImage.setWriteProtected(protect);
        }
    }
}
