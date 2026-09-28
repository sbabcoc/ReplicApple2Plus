package com.nordstrom.emulator.input;

/**
 * One method, and the only reason this class exists: to be
 * <i>defined by the same class loader as Jamepad's own classes</i> and
 * call {@link System#load} from there.
 * <p>
 * The JVM binds a native library to the class loader of the class that
 * loaded it, and a native method is only found in libraries bound to its
 * own class's loader. So the library has to be loaded by a class living
 * in Jamepad's loader; loading it from this project's loader leaves
 * Jamepad's native methods unresolved (an {@code UnsatisfiedLinkError}).
 * {@link JamepadProvider} therefore defines a copy of this class inside
 * its private Jamepad loader, from these very bytes, and calls it by
 * reflection.
 * <p>
 * It must stay dependency-free -- nothing but the JDK -- because it is
 * loaded into a loader that cannot see the rest of this project.
 */
public final class JamepadNativeHook {

    /**
     * Loads a native library, bound to whichever loader defined this class.
     *
     * @param absolutePath the library file to load
     */
    public static void load(String absolutePath) {
        System.load(absolutePath);
    }

    private JamepadNativeHook() { }
}
