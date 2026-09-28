package com.nordstrom.emulator.input;

/**
 * Where mapped input ends up: the two things the Apple II's game
 * connector has. The real implementation is {@link BusInputSink}; tests
 * substitute one that just records what it is told.
 */
public interface InputSink {

    /**
     * Sets one paddle's dial position.
     *
     * @param channel 0-3
     * @param position 0-255, with 128 at the center
     */
    void setPaddle(int channel, int position);

    /**
     * Sets one pushbutton.
     *
     * @param button 0-2
     * @param pressed true while held
     */
    void setButton(int button, boolean pressed);
}
