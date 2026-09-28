package com.nordstrom.emulator.input;

/**
 * A source of pad state: some library or device that can be asked, over
 * and over, "what is the pad doing right now?". Everything platform- or
 * library-specific lives behind this interface, which is what keeps the
 * mapping identical on every operating system.
 * <p>
 * <b>Threading:</b> an implementation may be tied to the thread that
 * created it (SDL is), so all of {@link #poll}, {@link #description} and
 * {@link #close} must be called from the same thread that created the
 * provider. {@link PadPoller} guarantees that.
 */
public interface PadProvider extends AutoCloseable {

    /**
     * Reads the pad's current state.
     *
     * @return the state now, or {@link PadSnapshot#NEUTRAL} if no pad is connected
     */
    PadSnapshot poll();

    /**
     * Names what is currently supplying input, for the startup and
     * hotplug messages.
     *
     * @return the connected pad's name, or a note that none is connected
     */
    String description();

    /** Releases whatever the provider holds. */
    @Override
    void close();
}
