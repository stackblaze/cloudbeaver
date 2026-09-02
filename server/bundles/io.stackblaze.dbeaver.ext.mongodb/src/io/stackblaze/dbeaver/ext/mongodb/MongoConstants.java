package io.stackblaze.dbeaver.ext.mongodb;

public final class MongoConstants {
    public static final String DRIVER_ID = "mongodb";
    public static final String PROVIDER_ID = "mongodb";
    public static final String FULL_DRIVER_ID = "mongodb:mongodb";
    public static final int DEFAULT_PORT = 27017;
    public static final String DEFAULT_AUTH_SOURCE = "admin";
    public static final String PROP_AUTH_SOURCE = "authSource";
    public static final String PROP_AUTH_MECHANISM = "authMechanism";
    public static final String PROP_TLS = "tls";
    public static final String PROP_TLS_INSECURE = "tlsInsecure";
    public static final String MECH_SCRAM_SHA_256 = "scram-sha-256";
    public static final String MECH_SCRAM_SHA_1 = "scram-sha-1";
    public static final String MECH_PLAIN = "plain";
    public static final int MAX_DATABASES = 200;
    public static final int MAX_COLLECTIONS = 500;
    public static final int DEFAULT_MAX_DOCUMENTS = 200;

    private MongoConstants() {
    }
}
