package io.stackblaze.dbeaver.ext.s3.fs;

import io.minio.MinioClient;
import io.stackblaze.dbeaver.ext.s3.RustfsConstants;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.utils.CommonUtils;

public final class RustfsS3ClientFactory {

    private RustfsS3ClientFactory() {
    }

    @NotNull
    public static MinioClient createClient(@NotNull DBPConnectionConfiguration config) {
        String host = CommonUtils.notEmpty(config.getHostName());
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = CommonUtils.toInt(config.getHostPort(), RustfsConstants.DEFAULT_PORT);
        String accessKey = CommonUtils.notEmpty(config.getUserName());
        String secretKey = CommonUtils.notEmpty(config.getUserPassword());
        boolean useSsl = CommonUtils.toBoolean(
            config.getProviderProperty(RustfsConstants.PROP_USE_SSL),
            false
        );
        String region = CommonUtils.notEmpty(
            config.getProviderProperty(RustfsConstants.PROP_REGION)
        );
        if (region.isEmpty()) {
            region = "us-east-1";
        }

        MinioClient.Builder builder = MinioClient.builder()
            .endpoint(host, port, useSsl)
            .credentials(accessKey, secretKey)
            .region(region);

        // pathStyle is stored for RustFS compatibility; MinIO SDK uses path-style for custom endpoints.
        CommonUtils.toBoolean(config.getProviderProperty(RustfsConstants.PROP_PATH_STYLE), true);

        return builder.build();
    }
}
