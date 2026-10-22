package incari.classlib.nio;

import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Opens {@code FileChannel}s for the browser build, over {@link RandomAccessFile} and TeaVM's
 * virtual file system, and gives the file streams their channels.
 *
 * <p>Differences from the JDK, all forced by the page:
 *
 * <ul>
 *   <li>Initial file attributes can't be set: the virtual file system has none. Passing any throws
 *       {@link UnsupportedOperationException}, as the JDK does for attributes it can't set.
 *   <li>{@code SYNC} and {@code DSYNC} open the file in {@code "rws"}/{@code "rwd"} mode, and
 *       {@code force} does nothing: there is no disk cache between the page and its storage.
 *   <li>{@code NOFOLLOW_LINKS} and {@code SPARSE} are accepted and ignored (no links, no sparse
 *       files). {@code DELETE_ON_CLOSE} deletes the file when the channel closes, best effort.
 *   <li>A directory opened for reading gives a channel whose reads fail, as on Linux.
 * </ul>
 */
public final class FileChannels {

    private static final int UNKNOWN = 0;
    private static final int PRESERVES = 1;
    private static final int TRUNCATES = 2;

    // Whether opening an existing file "rw" empties it: TeaVM's RandomAccessFile does, the
    // JDK's doesn't. Found out once, by trying (readWriteOpenTruncates).
    private static volatile int readWriteOpen = UNKNOWN;

    // Each stream's channel, so getChannel() keeps returning the same one. Weak both ways: the
    // channel refers to its stream, so a strong value would keep the key alive forever.
    // Guarded by the map itself.
    private static final Map<Object, WeakReference<TFileChannel>> STREAM_CHANNELS = new WeakHashMap<>();

    private FileChannels() {}

    /** {@code FileChannel.open(Path, Set, FileAttribute...)}. */
    public static TFileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        Objects.requireNonNull(path);
        boolean read = false;
        boolean write = false;
        boolean append = false;
        boolean truncate = false;
        boolean create = false;
        boolean createNew = false;
        boolean deleteOnClose = false;
        boolean sync = false;
        boolean dsync = false;
        for (OpenOption option : options) {
            Objects.requireNonNull(option);
            if (option instanceof StandardOpenOption standard) {
                switch (standard) {
                    case READ -> read = true;
                    case WRITE -> write = true;
                    case APPEND -> append = true;
                    case TRUNCATE_EXISTING -> truncate = true;
                    case CREATE -> create = true;
                    case CREATE_NEW -> createNew = true;
                    case DELETE_ON_CLOSE -> deleteOnClose = true;
                    case SYNC -> sync = true;
                    case DSYNC -> dsync = true;
                    case SPARSE -> {
                        // Files in the page have no holes to leave; a hint the JDK may ignore too.
                    }
                }
            } else if (option != LinkOption.NOFOLLOW_LINKS) {
                // The virtual file system has no links, so not following them changes nothing.
                throw new UnsupportedOperationException(option + " not supported");
            }
        }
        if (attrs.length > 0) {
            throw new UnsupportedOperationException(
                    "'" + attrs[0].name() + "' not supported as initial attribute in the browser");
        }
        if (append) {
            if (read) {
                throw new IllegalArgumentException("READ + APPEND not allowed");
            }
            if (truncate) {
                throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
            }
            write = true;
        }
        if (!read && !write) {
            read = true;
        }

        File file = path.toFile();
        if (file.isDirectory()) {
            if (write) {
                throw new FileSystemException(path.toString(), null, "Is a directory");
            }
            return new DirectoryChannel(path.toString());
        }
        if (write && createNew) {
            if (!createFile(file, path)) {
                throw new FileAlreadyExistsException(path.toString());
            }
        } else if (!file.exists()) {
            if (!write || !create) {
                throw new NoSuchFileException(path.toString());
            }
            createFile(file, path);
        }

        RandomAccessFile raf;
        try {
            if (!write) {
                raf = new RandomAccessFile(file, "r");
            } else {
                raf = openForWriting(file, sync ? "rws" : dsync ? "rwd" : "rw", truncate);
            }
        } catch (FileNotFoundException e) {
            // The file exists by now, so the platform refused it.
            IOException refused = file.exists()
                    ? new AccessDeniedException(path.toString(), null, e.getMessage())
                    : new NoSuchFileException(path.toString());
            refused.initCause(e);
            throw refused;
        }
        Object lockKey = path.toAbsolutePath().normalize().toString();
        return new RandomAccessFileChannel(raf, read, write, append, deleteOnClose ? file : null, lockKey);
    }

    /** {@code RandomAccessFile.getChannel()}: a channel sharing the file's pointer. */
    public static TFileChannel of(RandomAccessFile file) {
        // Whether the file was opened read-only is private to it, so the channel allows both and
        // the file itself refuses writes it can't do.
        return channelOf(file, f -> new RandomAccessFileChannel(f, true, true, false, null, new Object()));
    }

    /** {@code FileInputStream.getChannel()}. See {@link StreamFileChannel} for its limits. */
    public static TFileChannel of(FileInputStream in) {
        return channelOf(in, StreamFileChannel::reading);
    }

    /** {@code FileOutputStream.getChannel()}. See {@link StreamFileChannel} for its limits. */
    public static TFileChannel of(FileOutputStream out) {
        return channelOf(out, StreamFileChannel::writing);
    }

    private static <T> TFileChannel channelOf(T owner, Function<T, TFileChannel> factory) {
        synchronized (STREAM_CHANNELS) {
            WeakReference<TFileChannel> ref = STREAM_CHANNELS.get(owner);
            TFileChannel channel = ref == null ? null : ref.get();
            if (channel == null) {
                channel = factory.apply(owner);
                STREAM_CHANNELS.put(owner, new WeakReference<>(channel));
            }
            return channel;
        }
    }

    /** Creates {@code file}; false if it already exists. */
    private static boolean createFile(File file, Path path) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new NoSuchFileException(path.toString());
        }
        return file.createNewFile();
    }

    /**
     * Opens {@code file} for reading and writing, keeping its content unless {@code truncate}.
     * Where opening "rw" empties the file (TeaVM), the content is read first and put back.
     */
    private static RandomAccessFile openForWriting(File file, String mode, boolean truncate) throws IOException {
        long length = file.length();
        byte @Nullable [] content = null;
        if (!truncate && length > 0 && readWriteOpenTruncates()) {
            content = new byte[(int) length];
            try (RandomAccessFile original = new RandomAccessFile(file, "r")) {
                original.readFully(content);
            }
        }
        RandomAccessFile raf = new RandomAccessFile(file, mode);
        try {
            if (truncate) {
                raf.setLength(0);
            } else if (content != null && raf.length() != content.length) {
                raf.setLength(0);
                raf.write(content);
                raf.seek(0);
            }
        } catch (IOException | RuntimeException e) {
            try {
                raf.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return raf;
    }

    private static boolean readWriteOpenTruncates() {
        int state = readWriteOpen;
        if (state == UNKNOWN) {
            state = probeReadWriteOpen() ? TRUNCATES : PRESERVES;
            readWriteOpen = state;
        }
        return state == TRUNCATES;
    }

    private static boolean probeReadWriteOpen() {
        try {
            File probe = File.createTempFile("incari-", ".probe");
            try {
                try (RandomAccessFile out = new RandomAccessFile(probe, "rw")) {
                    out.write(1);
                }
                try (RandomAccessFile again = new RandomAccessFile(probe, "rw")) {
                    return again.length() == 0;
                }
            } finally {
                probe.delete();
            }
        } catch (IOException e) {
            // Assume the worst: keeping content wrongly costs a copy, losing it costs the file.
            return true;
        }
    }

    /**
     * A directory opened for reading. The JDK allows the open (so a directory can be forced) and
     * fails the reads.
     */
    private static final class DirectoryChannel extends BaseFileChannel {

        private final String path;

        DirectoryChannel(String path) {
            super(true, false, new Object());
            this.path = path;
        }

        @Override
        int implRead(ByteBuffer dst) throws IOException {
            throw isDirectory();
        }

        @Override
        void implWrite(ByteBuffer src) throws IOException {
            throw isDirectory();
        }

        @Override
        int implRead(ByteBuffer dst, long position) throws IOException {
            throw isDirectory();
        }

        @Override
        void implWrite(ByteBuffer src, long position) throws IOException {
            throw isDirectory();
        }

        @Override
        long implPosition() {
            return 0;
        }

        @Override
        void implPosition(long newPosition) {}

        @Override
        long implSize() {
            return 0;
        }

        @Override
        void implTruncate(long size) {}

        @Override
        void implForce() {}

        @Override
        void implClose() {}

        private IOException isDirectory() {
            return new IOException(path + ": Is a directory");
        }
    }
}
