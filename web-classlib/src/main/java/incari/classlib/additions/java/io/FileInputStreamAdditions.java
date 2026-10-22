package incari.classlib.additions.java.io;

import incari.classlib.InstanceMethod;
import incari.classlib.Replaces;
import incari.classlib.nio.FileChannels;
import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import java.io.FileInputStream;
import java.io.IOException;

/** {@code java.io.FileInputStream} methods TeaVM lacks, or has wrong. */
public final class FileInputStreamAdditions {

    private FileInputStreamAdditions() {}

    /**
     * The stream's channel, which reads through the stream and so shares its position. TeaVM's
     * stream hides its file handle, so the channel can't seek or size the file (see
     * {@code incari.classlib.nio.StreamFileChannel}).
     */
    @InstanceMethod
    public static TFileChannel getChannel(FileInputStream self) {
        return FileChannels.of(self);
    }

    /**
     * The next byte, 0 to 255, or -1 at the end. TeaVM's returns the byte signed, so a byte
     * of 0x80 or more reads as negative: a {@code DataInputStream} straight over the file (the
     * game's {@code NbtIo.read(Path)}, which reads {@code servers.dat}) took it for the end.
     */
    @InstanceMethod
    @Replaces
    public static int read(FileInputStream self) throws IOException {
        byte[] one = new byte[1];
        return self.read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
    }
}
