package incari.classlib.additions.java.io;

import incari.classlib.InstanceMethod;
import incari.classlib.nio.FileChannels;
import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import java.io.RandomAccessFile;

/** {@code java.io.RandomAccessFile} methods TeaVM lacks. */
public final class RandomAccessFileAdditions {

    private RandomAccessFileAdditions() {}

    /**
     * The file's channel, whose position is the file's pointer. Closing the channel closes the
     * file; closing the file is only noticed by the channel's next operation, which fails.
     */
    @InstanceMethod
    public static TFileChannel getChannel(RandomAccessFile self) {
        return FileChannels.of(self);
    }
}
