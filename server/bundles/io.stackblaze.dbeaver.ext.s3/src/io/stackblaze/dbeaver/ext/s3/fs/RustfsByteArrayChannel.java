package io.stackblaze.dbeaver.ext.s3.fs;

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.nio.ByteArrayChannel;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.Set;

/**
 * In-memory channel for S3 object bytes. Reads serve the downloaded buffer;
 * writes accumulate in memory and are uploaded via the sink on close
 * (S3 has no partial writes — objects are replaced atomically).
 */
public class RustfsByteArrayChannel extends ByteArrayChannel {

    /** Uploads the final object bytes on channel close. */
    public interface WriteSink {
        void write(byte[] data) throws IOException;
    }

    @Nullable
    private final WriteSink writeSink;

    public RustfsByteArrayChannel(byte[] buf, Set<? extends OpenOption> options) {
        this(buf, options, null);
    }

    public RustfsByteArrayChannel(byte[] buf, Set<? extends OpenOption> options, @Nullable WriteSink writeSink) {
        super(buf, options);
        this.writeSink = writeSink;
    }

    @Override
    protected void createNewFile() throws IOException {
        if (writeSink == null) {
            throw new IOException("Creating S3 objects via NIO is not supported");
        }
        // Object materializes when the written bytes are uploaded in writeToFile().
    }

    @Override
    protected void writeToFile() throws IOException {
        if (writeSink == null) {
            throw new IOException("Writing S3 objects via NIO is not supported");
        }
        writeSink.write(toByteArray());
    }

    @Override
    protected void deleteFile() throws IOException {
        throw new IOException("DELETE_ON_CLOSE is not supported for S3 objects");
    }
}
