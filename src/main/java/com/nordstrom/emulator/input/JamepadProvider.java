package com.nordstrom.emulator.input;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Reads a gamepad through Jamepad (an SDL binding), which is not a
 * dependency of this project.
 * <p>
 * Everything about Jamepad is reached by reflection, on purpose. This
 * project is published as a library with no runtime dependencies, and
 * that should stay true; a gamepad is an optional extra. So the Jamepad
 * jar is supplied at run time, by placing it in the directory given with
 * {@code --plugins}, and if it is not there this provider quietly does
 * not exist and everything else works as before.
 * <p>
 * Jamepad normally loads its own native library through a libGDX helper
 * class that its jar references but does not contain. That helper is
 * bypassed here: the right native library is chosen for the platform
 * ({@link NativeLibraryChooser}), extracted from the jar, and loaded
 * directly, which Jamepad supports via its {@code loadNativeLibrary}
 * setting. The library's JNI entry points use standard names, so this
 * works without the helper.
 * <p>
 * <b>Why a private class loader.</b> A native library is bound to the
 * class loader that loaded it, and Jamepad's native methods only find
 * libraries bound to <i>their</i> loader. When the jar is supplied
 * through {@code --plugins} its classes belong to the plugin loader, so
 * loading the library from this project's own classes leaves them
 * unresolved. This class therefore finds the Jamepad jar wherever the
 * loader it is given can see it (class path or plugins directory alike),
 * opens it in a loader of its own, and loads the library through
 * {@link JamepadNativeHook}, defined inside that same loader. Both ways
 * of supplying the jar take exactly this path.
 * <p>
 * Only the first connected pad is read.
 */
public final class JamepadProvider implements PadProvider {

    private static final String PACKAGE = "com.studiohartman.jamepad.";
    private static final float TRIGGER_THRESHOLD = 0.5f;
    private static final String NONE_CONNECTED = "no gamepad connected";
    private static final String MARKER_CLASS = "com/studiohartman/jamepad/ControllerManager.class";
    private static final String HOOK_NAME = JamepadNativeHook.class.getName();
    private static final String JAR_HINT = "Gamepad support is off: the Jamepad jar was not found. To enable it, "
        + "put jamepad-2.30.0.0.jar (com.badlogicgames.jamepad:jamepad) in the directory given with --plugins, "
        + "or add it to the class path.";

    private final URLClassLoader jamepadLoader;
    private final Object manager;
    private final Method update;
    private final Method getNumControllers;
    private final Method getState;
    private final Method getControllerIndex;
    private final Method quit;
    private final Field isConnected;
    private final Field leftStickX;
    private final Field leftStickY;
    private final Field rightStickX;
    private final Field rightStickY;
    private final Field leftTrigger;
    private final Field rightTrigger;
    private final Field[] buttonFields;
    private final PadButton[] buttonTargets;

    private int lastCount = -1;
    private String description = NONE_CONNECTED;

    private JamepadProvider(URLClassLoader jamepadLoader, Object manager, Class<?> managerClass, Class<?> stateClass)
            throws ReflectiveOperationException {
        this.jamepadLoader = jamepadLoader;
        this.manager = manager;
        this.update = managerClass.getMethod("update");
        this.getNumControllers = managerClass.getMethod("getNumControllers");
        this.getState = managerClass.getMethod("getState", int.class);
        this.getControllerIndex = managerClass.getMethod("getControllerIndex", int.class);
        this.quit = managerClass.getMethod("quitSDLGamepad");
        this.isConnected = stateClass.getField("isConnected");
        this.leftStickX = stateClass.getField("leftStickX");
        this.leftStickY = stateClass.getField("leftStickY");
        this.rightStickX = stateClass.getField("rightStickX");
        this.rightStickY = stateClass.getField("rightStickY");
        this.leftTrigger = stateClass.getField("leftTrigger");
        this.rightTrigger = stateClass.getField("rightTrigger");

        String[][] names = {
            {"a", "A"}, {"b", "B"}, {"x", "X"}, {"y", "Y"},
            {"back", "BACK"}, {"guide", "GUIDE"}, {"start", "START"},
            {"leftStickClick", "LEFT_STICK"}, {"rightStickClick", "RIGHT_STICK"},
            {"lb", "LEFT_BUMPER"}, {"rb", "RIGHT_BUMPER"},
            {"dpadUp", "DPAD_UP"}, {"dpadDown", "DPAD_DOWN"},
            {"dpadLeft", "DPAD_LEFT"}, {"dpadRight", "DPAD_RIGHT"},
            {"misc1", "MISC1"},
        };
        buttonFields = new Field[names.length];
        buttonTargets = new PadButton[names.length];
        for (int i = 0; i < names.length; i++) {
            buttonFields[i] = stateClass.getField(names[i][0]);
            buttonTargets[i] = PadButton.valueOf(names[i][1]);
        }
    }

    /**
     * Starts Jamepad if it is available. Call this on the thread that
     * will poll: SDL is initialized here and must be used from the same
     * thread afterwards.
     *
     * @param loader a class loader that can see the Jamepad jar -- either the application's own or
     *               the {@code --plugins} one
     * @param log where to explain why gamepad support is unavailable
     * @return a working provider, or null if gamepad support is unavailable (the reason is logged)
     */
    public static PadProvider create(ClassLoader loader, PrintStream log) {
        URL jamepadRoot = locateJamepad(loader);
        if (jamepadRoot == null) {
            log.println("input: " + JAR_HINT);
            return null;
        }

        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        Optional<String> nativeName = NativeLibraryChooser.choose(os, arch);
        if (nativeName.isEmpty()) {
            log.println("input: gamepad support is not available on this platform (" + os + ", " + arch
                + "): the Jamepad jar has no native library for it.");
            return null;
        }

        JamepadLoader jamepadLoader = null;
        boolean succeeded = false;
        try {
            jamepadLoader = new JamepadLoader(jamepadRoot, loader, readHookBytes());
            Class<?> configClass = Class.forName(PACKAGE + "Configuration", true, jamepadLoader);
            Class<?> managerClass = Class.forName(PACKAGE + "ControllerManager", true, jamepadLoader);
            Class<?> stateClass = Class.forName(PACKAGE + "ControllerState", true, jamepadLoader);

            loadNative(jamepadLoader, nativeName.get());
            Object configuration = configClass.getConstructor().newInstance();
            configClass.getField("loadNativeLibrary").setBoolean(configuration, false);
            Constructor<?> constructor = managerClass.getConstructor(configClass);
            Object manager = constructor.newInstance(configuration);
            initQuietly(manager, managerClass);
            JamepadProvider provider = new JamepadProvider(jamepadLoader, manager, managerClass, stateClass);
            succeeded = true;
            return provider;
        } catch (InvocationTargetException e) {
            log.println("input: gamepad support failed to start: " + e.getCause());
        } catch (IOException | ReflectiveOperationException | LinkageError e) {
            log.println("input: gamepad support failed to start: " + e);
        } finally {
            if (!succeeded && jamepadLoader != null) {
                try {
                    jamepadLoader.close();
                } catch (IOException ignored) {
                    // nothing more to do for a loader that never became a provider
                }
            }
        }
        return null;
    }

    /**
     * Finds where Jamepad lives, as a URL a class loader can be opened
     * over: the jar file (or class directory) that supplies
     * {@code ControllerManager} to {@code loader}.
     */
    private static URL locateJamepad(ClassLoader loader) {
        URL marker = loader.getResource(MARKER_CLASS);
        if (marker == null) {
            return null;
        }
        String text = marker.toString();
        String base;
        if (text.startsWith("jar:")) {
            base = text.substring("jar:".length(), text.lastIndexOf("!/"));
        } else if (text.endsWith(MARKER_CLASS)) {
            base = text.substring(0, text.length() - MARKER_CLASS.length());
        } else {
            return null;
        }
        try {
            return URI.create(base).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] readHookBytes() throws IOException {
        String resource = HOOK_NAME.substring(HOOK_NAME.lastIndexOf('.') + 1) + ".class";
        try (InputStream in = JamepadNativeHook.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " is missing -- this is a packaging bug");
            }
            return in.readAllBytes();
        }
    }

    /**
     * Extracts the native library from the jar and loads it, bound to the
     * Jamepad loader by way of {@link JamepadNativeHook}.
     */
    private static synchronized void loadNative(JamepadLoader loader, String name)
            throws IOException, ReflectiveOperationException {
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " is not in the Jamepad jar");
            }
            String suffix = name.substring(name.lastIndexOf('.'));
            Path temp = Files.createTempFile("jamepad", suffix);
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Class<?> hook = Class.forName(HOOK_NAME, true, loader);
            hook.getMethod("load", String.class).invoke(null, temp.toAbsolutePath().toString());
        }
    }

    /**
     * A private loader over the Jamepad jar. Jamepad's own classes are
     * taken from the jar first, never from a parent that might also see
     * them, and {@link JamepadNativeHook} is defined here from bytes, so
     * both share this loader -- the requirement for the native library
     * to be found. Everything else (the JDK) delegates normally.
     */
    private static final class JamepadLoader extends URLClassLoader {
        private final byte[] hookBytes;

        JamepadLoader(URL root, ClassLoader parent, byte[] hookBytes) {
            super(new URL[] {root}, parent);
            this.hookBytes = hookBytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && name.equals(HOOK_NAME)) {
                    loaded = defineClass(name, hookBytes, 0, hookBytes.length);
                }
                if (loaded == null && name.startsWith(PACKAGE)) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // not ours after all; let the normal delegation decide
                    }
                }
                if (loaded == null) {
                    return super.loadClass(name, resolve);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    /**
     * Jamepad's initialization prints a full stack trace to stderr when
     * its optional {@code gamecontrollerdb.txt} is absent from the jar,
     * then carries on with SDL's built-in mappings. That is harmless and
     * looks like a crash, so it is held back and dropped -- but only that
     * one known message. Anything else it prints, and everything if
     * initialization fails, is passed through untouched.
     */
    private static void initQuietly(Object manager, Class<?> managerClass) throws ReflectiveOperationException {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean succeeded = false;
        try {
            System.setErr(new PrintStream(captured, true));
            managerClass.getMethod("initSDLGamepad").invoke(manager);
            succeeded = true;
        } finally {
            System.setErr(original);
            String text = captured.toString();
            if (!succeeded || (!text.isEmpty() && !text.contains("gamecontrollerdb.txt"))) {
                original.print(text);
            }
        }
    }

    @Override
    public PadSnapshot poll() {
        try {
            update.invoke(manager);
            int count = (Integer) getNumControllers.invoke(manager);
            if (count != lastCount) {
                lastCount = count;
                description = count == 0 ? NONE_CONNECTED : "gamepad connected: " + nameOfFirst();
            }
            if (count == 0) {
                return PadSnapshot.NEUTRAL;
            }
            Object state = getState.invoke(manager, 0);
            if (!isConnected.getBoolean(state)) {
                return PadSnapshot.NEUTRAL;
            }
            PadSnapshot.Builder snapshot = PadSnapshot.builder()
                .axis(PadAxis.LEFT_X, leftStickX.getFloat(state))
                .axis(PadAxis.LEFT_Y, leftStickY.getFloat(state))
                .axis(PadAxis.RIGHT_X, rightStickX.getFloat(state))
                .axis(PadAxis.RIGHT_Y, rightStickY.getFloat(state));
            for (int i = 0; i < buttonFields.length; i++) {
                if (buttonFields[i].getBoolean(state)) {
                    snapshot.press(buttonTargets[i]);
                }
            }
            if (leftTrigger.getFloat(state) > TRIGGER_THRESHOLD) {
                snapshot.press(PadButton.LEFT_TRIGGER);
            }
            if (rightTrigger.getFloat(state) > TRIGGER_THRESHOLD) {
                snapshot.press(PadButton.RIGHT_TRIGGER);
            }
            return snapshot.build();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Jamepad failed: " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Jamepad's API is not what this provider expects: " + e, e);
        }
    }

    private String nameOfFirst() {
        try {
            Object index = getControllerIndex.invoke(manager, 0);
            return (String) index.getClass().getMethod("getName").invoke(index);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "(unnamed)";
        }
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public void close() {
        try {
            quit.invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // shutting down anyway
        }
        try {
            jamepadLoader.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
    }
}
