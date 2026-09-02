package io.stackblaze.dbeaver.ext.files;

public final class FilesConstants {
    public static final String DRIVER_ID = "http";
    public static final String PROVIDER_ID = "files";
    public static final String FULL_DRIVER_ID = "files:http";
    public static final int DEFAULT_PORT = 8080;
    public static final int PREVIEW_CHARS = 8_192;
    /** Hard ceiling for a real (Save-to-file) content fetch — matches the sidecar's own cap. */
    public static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024;

    private FilesConstants() {
    }
}
