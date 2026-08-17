package io.stackblaze.dbeaver.ext.s3;

public final class RustfsConstants {
    public static final String DRIVER_ID = "rustfs";
    public static final String PROVIDER_ID = "s3";
    public static final String FULL_DRIVER_ID = "s3:rustfs";
    public static final String FS_PROVIDER_ID = "rustfs-s3";
    public static final String FS_TYPE = "s3";
    public static final String NIO_SCHEME = "s3";

    public static final int DEFAULT_PORT = 9000;

    public static final String PROP_PATH_STYLE = "pathStyle";
    public static final String PROP_USE_SSL = "useSsl";
    public static final String PROP_REGION = "region";

    private RustfsConstants() {
    }
}
