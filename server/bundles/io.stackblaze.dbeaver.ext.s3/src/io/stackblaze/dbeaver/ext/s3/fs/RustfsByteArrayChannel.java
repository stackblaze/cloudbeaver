package io.stackblaze.dbeaver.ext.s3.fs;

import org.jkiss.dbeaver.model.nio.ByteArrayChannel;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.Set;

/** Read-only in-memory channel for S3 object bytes. */
public class RustfsByteArrayChannel extends ByteArrayChannel {

    public RustfsByteArrayChannel(byte[] buf, Set<? extends OpenOption> options) {
        super(buf, options);
    }

    @Override
    protected void createNewFile() throws IOException {
        throw new IOException("Creating S3 objects via NIO is not supported");
    }

    @Override
    protected void writeToFile() throws IOException {
        throw new IOException("Writing S3 objects via NIO is not supported");
    }

    @Override
    protected void deleteFile() throws IOException {
        throw new IOException("Deleting S3 objects via NIO is not supported");
    }
}
