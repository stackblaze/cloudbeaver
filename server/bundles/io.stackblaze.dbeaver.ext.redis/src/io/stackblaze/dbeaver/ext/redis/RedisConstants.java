package io.stackblaze.dbeaver.ext.redis;

public final class RedisConstants {
    public static final String DRIVER_ID = "redis";
    public static final String PROVIDER_ID = "redis";
    public static final String FULL_DRIVER_ID = "redis:redis";
    public static final int DEFAULT_PORT = 6379;
    public static final int DEFAULT_DB = 0;
    public static final int MAX_KEYS = 500;
    public static final int SCAN_COUNT = 200;

    private RedisConstants() {
    }
}
