import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Regression probe for web-classlib's FileInputStreamAdditions, and for RandomAccessFile's own
 * read() since TeaVM 0.16.0: TeaVM's single-byte {@code read()} returned bytes signed, so a byte of
 * 0x80 or more read as the end of the file, and the game's {@code NbtIo.read(Path)} (a
 * DataInputStream straight over the file) lost {@code servers.dat}. Prints "probe passed" when every
 * byte reads back unsigned.
 */
public final class FileReadProbe {

    private FileReadProbe() {}

    public static void main(String[] args) throws IOException {
        File file = new File("/probe.dat");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {0x0A, (byte) 0xC8, (byte) 0xFF, 0x00, 0x01});
        }
        StringBuilder problems = new StringBuilder();
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int first = in.readUnsignedByte();
            int second = in.readUnsignedShort();
            short third = in.readShort();
            if (first != 0x0A || second != 0xC8FF || third != 1) {
                problems.append(" stream: ").append(first).append(',').append(second).append(',').append(third);
            }
            if (in.read() != -1) {
                problems.append(" stream end");
            }
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(1);
            if (raf.read() != 0xC8 || raf.readUnsignedByte() != 0xFF) {
                problems.append(" random access");
            }
        }
        System.out.println(problems.isEmpty() ? "probe passed" : "probe failed:" + problems);
    }
}
