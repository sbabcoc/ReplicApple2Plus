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
 * This is a first slice, not a complete controller. Three things are
 * genuinely ready now and implemented for real:
 * <ul>
 *   <li>The boot ROM at $Cn00-$CnFF ({@link DiskBootRom}, already
 *       verified against two independent sources)</li>
 *   <li>The 16 soft switches' CONTROL semantics at $C0n0-$C0nF -- phase
 *       stepper magnets, motor on/off, drive select, and the Q6/Q7 mode
 *       latches themselves. These are static, well-documented hardware
 *       facts with no timing dependency, so there's nothing provisional
 *       about implementing them now.</li>
 *   <li>Loading a real WOZ disk image ({@link WozDiskImage}) on
 *       {@link Drive#insert} -- genuine bit-level track data, parsed and
 *       verified, sitting ready to be read.</li>
 * </ul>
 * What's deliberately NOT implemented: actual bit-level data returned
 * when reading through Q6/Q7 (the disk read/write data latch). That
 * requires the LSS sequencer ticking once every 4 CPU cycles against
 * {@link WozDiskImage}'s bitstream -- the WOZ side of that is done now,
 * but the LSS sequencer itself, and wiring it to
 * {@code SystemClock.addCycleListener} the same way {@code PaddleTimers}
 * and {@code VideoScanner} already do, is not, so faking a
 * plausible-looking data byte here would be exactly the kind of
 * confident wrong guess this project has avoided everywhere else.
 * Reading the data latch (offsets $C-$F) throws, naming the gap.
 * Writing to those same offsets still applies the real state change
 * (which mode is selected) -- only the BYTE VALUE a read would expose is
 * unimplemented, not the switch itself.
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
 * return value IS the data real software reads disk bytes through,
 * which is exactly the part not yet built.
 */
public final class Disk2Controller implements SlotCard {

    private final boolean[] phaseOn = new boolean[4];
    private boolean motorOn;
    private int selectedDrive; // 0 or 1
    private boolean q6;
    private boolean q7;

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
            throw new UnsupportedOperationException("Disk II data latch reads (Q6/Q7, offset $"
                + Integer.toHexString(offset) + ") depend on the not-yet-built LSS/SystemClock tick loop "
                + "and WOZ bitstream parser -- phase stepper, motor, and drive-select switches work; "
                + "actual bit-level disk data does not yet.");
        }
        return 0; // harmless -- real software never inspects this for offsets 0x0-0xB, see class Javadoc
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        applySwitch(offset);
    }

    private void applySwitch(int offset) {
        switch (offset) {
            case 0x0 -> phaseOn[0] = false;
            case 0x1 -> phaseOn[0] = true;
            case 0x2 -> phaseOn[1] = false;
            case 0x3 -> phaseOn[1] = true;
            case 0x4 -> phaseOn[2] = false;
            case 0x5 -> phaseOn[2] = true;
            case 0x6 -> phaseOn[3] = false;
            case 0x7 -> phaseOn[3] = true;
            case 0x8 -> motorOn = false;
            case 0x9 -> motorOn = true;
            case 0xA -> selectedDrive = 0;
            case 0xB -> selectedDrive = 1;
            case 0xC -> q6 = false;
            case 0xD -> q6 = true;
            case 0xE -> q7 = false;
            case 0xF -> q7 = true;
            default -> throw new IllegalArgumentException("offset " + offset + " is outside 0-15");
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
     * currently loaded, and now genuinely parses it via
     * {@link WozDiskImage} on insert -- real, verified bit-level track
     * data is available today via {@link #wozImage}. What's still
     * missing is the LSS sequencer that would actually read it in
     * response to the CPU polling Q6/Q7, a separate, not-yet-built
     * piece -- this class's own job (tracking which image is loaded and
     * making its data available) is done.
     */
    static final class Drive implements RemovableMediaDrive {

        private Path currentImage;
        private WozDiskImage wozImage;

        @Override
        public void insert(Path imagePath) throws IOException {
            if (!Files.exists(imagePath)) {
                throw new IOException("Disk image not found: " + imagePath);
            }
            wozImage = WozDiskImage.load(imagePath);
            currentImage = imagePath;
        }

        @Override
        public void eject() {
            currentImage = null;
            wozImage = null;
        }

        @Override
        public boolean isPresent() {
            return currentImage != null;
        }

        /** Package-visible for tests, and for the not-yet-built LSS to eventually consume. */
        WozDiskImage wozImage() {
            return wozImage;
        }

        @Override
        public Optional<Path> currentImagePath() {
            return Optional.ofNullable(currentImage);
        }
    }
}
