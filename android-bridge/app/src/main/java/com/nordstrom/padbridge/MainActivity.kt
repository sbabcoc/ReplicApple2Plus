package com.nordstrom.padbridge

import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a real, already-paired Bluetooth game controller through
 * Android's own standard, unprivileged game-controller input APIs
 * (InputDevice / MotionEvent / KeyEvent -- the same ones any Android
 * game uses) and sends its state as plain-text UDP packets to the
 * ReplicApple2Plus emulator's NetworkPadProvider, running inside
 * Termux/PRoot on the same device.
 *
 * NOT VERIFIED ON A REAL DEVICE. This was written against Android's
 * documented game-controller APIs, but never compiled or run -- there is
 * no Android SDK, emulator, or device available in the environment that
 * produced it. Treat this as a starting point, not a finished app.
 * Specific things likely to need adjustment on real hardware:
 *   - Which MotionEvent axis constants this specific controller actually
 *     reports for the right stick and the triggers (AXIS_Z/AXIS_RZ and
 *     AXIS_LTRIGGER/AXIS_RTRIGGER are the standard, documented mapping,
 *     but some controllers or Android versions report triggers as
 *     AXIS_BRAKE/AXIS_GAS instead -- this reads both and uses whichever
 *     is non-zero, but that fallback itself is unverified).
 *   - Whether onGenericMotionEvent/onKeyDown actually fire for every
 *     button on the SN30 Pro specifically, or whether some buttons need
 *     a different KeyEvent code than assumed here.
 *
 * Wire protocol: see NetworkPadProvider's own Javadoc in the Java
 * project. One UDP packet per update, ASCII text, either the literal
 * body "ABSENT" or space-separated KEY=VALUE pairs using the exact
 * PadAxis/PadButton names from that project (LEFT_X, RIGHT_TRIGGER,
 * BUTTONS=A,DPAD_UP, etc.).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8942 // must match NetworkPadProvider.DEFAULT_PORT
        private const val SEND_INTERVAL_MILLIS = 10L // matches Apple2Plus's own pad-poll rate
        private const val TRIGGER_THRESHOLD = 0.5f // matches JamepadProvider's own threshold, for consistency

        // Left stick is the standard, documented mapping for any Android
        // game controller. Right stick and triggers vary more by device;
        // these are the most common, but see the class Javadoc above.
        private val AXIS_LEFT_X = MotionEvent.AXIS_X
        private val AXIS_LEFT_Y = MotionEvent.AXIS_Y
        private val AXIS_RIGHT_X = MotionEvent.AXIS_Z
        private val AXIS_RIGHT_Y = MotionEvent.AXIS_RZ
        private val AXIS_LEFT_TRIGGER_PRIMARY = MotionEvent.AXIS_LTRIGGER
        private val AXIS_LEFT_TRIGGER_FALLBACK = MotionEvent.AXIS_BRAKE
        private val AXIS_RIGHT_TRIGGER_PRIMARY = MotionEvent.AXIS_RTRIGGER
        private val AXIS_RIGHT_TRIGGER_FALLBACK = MotionEvent.AXIS_GAS

        /** Every button this bridge forwards, and the KeyEvent code that triggers it. */
        private val BUTTON_KEY_CODES = mapOf(
            "A" to KeyEvent.KEYCODE_BUTTON_A,
            "B" to KeyEvent.KEYCODE_BUTTON_B,
            "X" to KeyEvent.KEYCODE_BUTTON_X,
            "Y" to KeyEvent.KEYCODE_BUTTON_Y,
            "BACK" to KeyEvent.KEYCODE_BUTTON_SELECT,
            "START" to KeyEvent.KEYCODE_BUTTON_START,
            "GUIDE" to KeyEvent.KEYCODE_BUTTON_MODE,
            "LEFT_STICK" to KeyEvent.KEYCODE_BUTTON_THUMBL,
            "RIGHT_STICK" to KeyEvent.KEYCODE_BUTTON_THUMBR,
            "LEFT_BUMPER" to KeyEvent.KEYCODE_BUTTON_L1,
            "RIGHT_BUMPER" to KeyEvent.KEYCODE_BUTTON_R1,
            "DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            // MISC1 has no clean, universal Android KeyEvent equivalent
            // across different controllers, so it's intentionally not
            // wired here. Nothing downstream requires it.
        )
    }

    // Current known state, updated on the UI thread from input events,
    // read from the background sender thread. AtomicLong packs the four
    // axis floats via Float.floatToIntBits into two longs for a simple,
    // allocation-free way to publish four floats atomically together;
    // buttons are a plain @Volatile since a single reference swap is
    // already atomic.
    private val leftAxes = AtomicLong(pack(0f, 0f))
    private val rightAxes = AtomicLong(pack(0f, 0f))
    @Volatile private var pressedButtons: Set<String> = emptySet()
    @Volatile private var controllerConnected = false
    @Volatile private var controllerName = ""

    private val running = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private val packetsSentCount = AtomicInteger(0)
    private var activeDeviceId: Int = -1

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = detectController()
        override fun onInputDeviceChanged(deviceId: Int) = detectController()
        override fun onInputDeviceRemoved(deviceId: Int) {
            // Only reacts if the controller actually in use was the one
            // removed -- some devices (an on-screen keyboard, say) can
            // legitimately come and go without a real controller ever
            // having been present at all.
            if (deviceId == activeDeviceId) {
                controllerConnected = false
                activeDeviceId = -1
                runOnUiThread { statusView.text = "Controller disconnected" }
            }
        }
    }

    @Volatile private var targetHost = DEFAULT_HOST
    @Volatile private var targetPort = DEFAULT_PORT

    private lateinit var statusView: TextView
    private lateinit var packetsSentView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.controllerStatus)
        packetsSentView = findViewById(R.id.packetsSent)
        val hostField = findViewById<EditText>(R.id.targetHost)
        val portField = findViewById<EditText>(R.id.targetPort)

        findViewById<Button>(R.id.applyButton).setOnClickListener {
            targetHost = hostField.text.toString().ifBlank { DEFAULT_HOST }
            targetPort = portField.text.toString().toIntOrNull() ?: DEFAULT_PORT
            statusView.text = "Target set to $targetHost:$targetPort"
        }

        detectController()
        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(deviceListener)
    }

    override fun onResume() {
        super.onResume()
        startSending()
    }

    override fun onPause() {
        super.onPause()
        stopSending()
    }

    /**
     * Looks for an already-connected game controller among all input
     * devices -- this is the standard, documented, unprivileged way any
     * Android app enumerates controllers; it needs no special permission.
     */
    private fun detectController() {
        for (deviceId in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            val sources = device.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (isGamepad || isJoystick) {
                controllerConnected = true
                activeDeviceId = deviceId
                controllerName = device.name
                runOnUiThread { statusView.text = "Controller: $controllerName" }
                return
            }
        }
        controllerConnected = false
        activeDeviceId = -1
        runOnUiThread { statusView.text = "No controller detected (is it paired and awake?)" }
    }

    /**
     * Android delivers real controller stick/trigger movement here, not
     * through onTouchEvent -- this is the standard callback for joystick
     * sources. Runs on the UI thread.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return super.onGenericMotionEvent(event)
        }
        controllerConnected = true

        val leftX = event.getAxisValue(AXIS_LEFT_X)
        val leftY = event.getAxisValue(AXIS_LEFT_Y)
        val rightX = event.getAxisValue(AXIS_RIGHT_X)
        val rightY = event.getAxisValue(AXIS_RIGHT_Y)
        leftAxes.set(pack(leftX, leftY))
        rightAxes.set(pack(rightX, rightY))

        val leftTrigger = betterOf(event.getAxisValue(AXIS_LEFT_TRIGGER_PRIMARY), event.getAxisValue(AXIS_LEFT_TRIGGER_FALLBACK))
        val rightTrigger = betterOf(event.getAxisValue(AXIS_RIGHT_TRIGGER_PRIMARY), event.getAxisValue(AXIS_RIGHT_TRIGGER_FALLBACK))
        updateTriggerButtons(leftTrigger, rightTrigger)

        return true
    }

    /** Whichever of a controller's two possible trigger axis sources is actually reporting something. */
    private fun betterOf(primary: Float, fallback: Float): Float =
        if (primary != 0f) primary else fallback

    private fun updateTriggerButtons(leftTrigger: Float, rightTrigger: Float) {
        val current = pressedButtons.toMutableSet()
        setButtonState(current, "LEFT_TRIGGER", leftTrigger > TRIGGER_THRESHOLD)
        setButtonState(current, "RIGHT_TRIGGER", rightTrigger > TRIGGER_THRESHOLD)
        pressedButtons = current
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return super.onKeyDown(keyCode, event)
        }
        controllerConnected = true
        applyButtonKey(keyCode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return super.onKeyUp(keyCode, event)
        }
        applyButtonKey(keyCode, false)
        return true
    }

    private fun applyButtonKey(keyCode: Int, isDown: Boolean) {
        val name = BUTTON_KEY_CODES.entries.firstOrNull { it.value == keyCode }?.key ?: return
        val current = pressedButtons.toMutableSet()
        setButtonState(current, name, isDown)
        pressedButtons = current
    }

    private fun setButtonState(set: MutableSet<String>, name: String, pressed: Boolean) {
        if (pressed) set.add(name) else set.remove(name)
    }

    private fun startSending() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        senderThread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                while (running.get()) {
                    sendOnePacket(socket)
                    SystemClock.sleep(SEND_INTERVAL_MILLIS)
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "Send failed: ${e.message}" }
            } finally {
                socket?.close()
            }
        }, "pad-sender").also { it.isDaemon = true; it.start() }
    }

    private fun stopSending() {
        running.set(false)
        senderThread?.join(500)
        senderThread = null
    }

    private fun sendOnePacket(socket: DatagramSocket) {
        val body = if (!controllerConnected) {
            "ABSENT"
        } else {
            val (lx, ly) = unpack(leftAxes.get())
            val (rx, ry) = unpack(rightAxes.get())
            val buttons = pressedButtons
            val buttonsField = if (buttons.isEmpty()) "BUTTONS=" else "BUTTONS=" + buttons.joinToString(",")
            "LEFT_X=%.3f LEFT_Y=%.3f RIGHT_X=%.3f RIGHT_Y=%.3f %s".format(lx, ly, rx, ry, buttonsField)
        }
        val bytes = body.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(targetHost), targetPort))
        val sent = packetsSentCount.incrementAndGet()
        if (sent % 100 == 0) { // update the UI occasionally, not on every single packet
            runOnUiThread { packetsSentView.text = "Packets sent: $sent" }
        }
    }

    private fun pack(a: Float, b: Float): Long =
        (java.lang.Float.floatToIntBits(a).toLong() shl 32) or (java.lang.Float.floatToIntBits(b).toLong() and 0xFFFFFFFFL)

    private fun unpack(packed: Long): Pair<Float, Float> {
        val a = java.lang.Float.intBitsToFloat((packed ushr 32).toInt())
        val b = java.lang.Float.intBitsToFloat(packed.toInt())
        return a to b
    }
}
