package com.smali_generator.likes;

/**
 * Logging seam. The core stays free of Android imports so it unit-tests on the
 * JVM; the device shell installs a sink that forwards to android.util.Log
 * under the PATCH tag.
 */
public final class Log {

    public interface Sink {
        void log(String message);
    }

    private static volatile Sink sink;

    private Log() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void w(String message) {
        Sink s = sink;
        if (s != null) {
            s.log(message);
        }
    }
}
