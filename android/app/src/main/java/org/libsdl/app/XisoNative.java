package org.libsdl.app;

/** JNI bridge to the existing, bounds-checked XDVDFS/XISO extractor. */
final class XisoNative {
    static {
        System.loadLibrary("jsrf_xiso");
    }

    private XisoNative() {
    }

    /** Returns an empty string on success, or a user-readable error. */
    static native String extractXiso(String imagePath, String destinationPath);
}
