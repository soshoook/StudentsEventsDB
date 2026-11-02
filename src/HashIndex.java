import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

class HashIndex implements AutoCloseable {
    static final int BUCKETS = 1 << 10;   //степень двойки
    static final int NIL = -1;
    private static final long HEADS_SIZE = (long) BUCKETS * 4;

    private final RandomAccessFile f;

    HashIndex(String path) throws IOException {
        f = new RandomAccessFile(path, "rw");
    }


    static long minSize() {
        return HEADS_SIZE;
    }

    //создание пустого инд
    static void create(String path) throws IOException {
        try (HashIndex ix = new HashIndex(path)) {
            ix.reset();
        }
    }

    //очищение инд
    void reset() throws IOException {
        f.setLength(0);
        byte[] heads = new byte[(int) HEADS_SIZE];
        Arrays.fill(heads, (byte) -1);   // четыре байта 0xFF = int -1
        f.write(heads);
    }

    @Override
    public void close() throws IOException {
        f.close();
    }

    private int readInt(long pos) throws IOException {
        byte[] b = new byte[4];
        f.seek(pos);
        f.readFully(b);
        return ByteBuffer.wrap(b).getInt();
    }

    private void writeInt(long pos, int v) throws IOException {
        f.seek(pos);
        f.write(ByteBuffer.allocate(4).putInt(v).array());
    }

    private static int bucketOf(Object key) {
        int h = key.hashCode();
        h ^= (h >>> 16);                 // перемешиваем старшие биты
        return h & (BUCKETS - 1);        // то же, что % BUCKETS
    }

    private static long nodePos(int slot) {
        return HEADS_SIZE + (long) slot * 8;
    }

    int head(Object key) throws IOException {
        return readInt((long) bucketOf(key) * 4);
    }

    int next(int slot) throws IOException {
        return readInt(nodePos(slot) + 4);
    }

    void link(int slot, Object key) throws IOException {
        long headPos = (long) bucketOf(key) * 4;
        int oldHead = readInt(headPos);

        ByteBuffer node = ByteBuffer.allocate(8);
        node.putInt(NIL).putInt(oldHead);          // prev = -1, next = старая голова
        f.seek(nodePos(slot));
        f.write(node.array());

        if (oldHead != NIL) {
            writeInt(nodePos(oldHead), slot);      // у старой головы prev = slot
        }
        writeInt(headPos, slot);                   // новая голова
    }

    void unlink(int slot, Object key) throws IOException {
        long headPos = (long) bucketOf(key) * 4;

        byte[] b = new byte[8];
        f.seek(nodePos(slot));
        f.readFully(b);
        ByteBuffer node = ByteBuffer.wrap(b);
        int prev = node.getInt();
        int next = node.getInt();

        if (prev == NIL) {
            writeInt(headPos, next);               // слот был головой
        } else {
            writeInt(nodePos(prev) + 4, next);     // у предыдущего next = следующий
        }
        if (next != NIL) {
            writeInt(nodePos(next), prev);         // у следующего prev = предыдущий
        }
    }
}
