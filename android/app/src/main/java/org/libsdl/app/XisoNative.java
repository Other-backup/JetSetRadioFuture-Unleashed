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

    /** Tests whether an already-open seekable descriptor contains an Xbox image. */
    static native boolean probeXisoFd(int fileDescriptor);

    /** Extracts without copying the full image to app storage first. */
    static native String extractXisoFd(int fileDescriptor, String destinationPath);
}
