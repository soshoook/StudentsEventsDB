import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

/*
 * Служебный файл: хеш-таблица на диске для ОДНОГО поля.
 *
 * Формат файла:
 *   [BUCKETS корзин по 4 байта: head] [узел слота 0: prev(4) next(4)] [узел слота 1] ...
 *
 *   head[b]  - номер слота, с которого начинается цепочка корзины b (-1 = пусто)
 *   узел слота s лежит по адресу BUCKETS*4 + s*8, то есть адрес узла считается по номеру слота.
 *   prev/next - соседи слота в цепочке (двусвязный список, -1 = конца нет)
 *
 * Значение поля -> hashCode -> номер корзины. Разные значения могут попасть в одну корзину
 * (коллизия), поэтому после поиска FileStorage сверяет значение в самой записи.
 *
 * Двусвязный список нужен, чтобы удалять слот из цепочки за O(1): соседи известны сразу,
 * по цепочке ходить не надо.
 */
class HashIndex implements AutoCloseable {
    static final int BUCKETS = 1 << 16;   // 65536 корзин, степень двойки
    static final int NIL = -1;
    private static final long HEADS_SIZE = (long) BUCKETS * 4;

    private final RandomAccessFile f;

    HashIndex(String path) throws IOException {
        f = new RandomAccessFile(path, "rw");
    }

    /** Минимальный размер корректного файла индекса. */
    static long minSize() {
        return HEADS_SIZE;
    }

    /** Создаёт пустой индекс (все корзины пустые). */
    static void create(String path) throws IOException {
        try (HashIndex ix = new HashIndex(path)) {
            ix.reset();
        }
    }

    /** Очищает индекс: файл обрезается и заполняется "пустыми" корзинами (-1). */
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

    // ---------- низкоуровневое чтение/запись (один вызов ОС на операцию) ----------

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

    // ---------- операции ----------

    /** Первый слот цепочки для значения key (-1, если цепочка пуста). */
    int head(Object key) throws IOException {
        return readInt((long) bucketOf(key) * 4);
    }

    /** Следующий слот в цепочке после slot (-1, если это конец). */
    int next(int slot) throws IOException {
        return readInt(nodePos(slot) + 4);
    }

    /** Добавляет слот в начало цепочки корзины значения key. O(1). */
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

    /** Убирает слот из цепочки корзины значения key (key - то же значение, что было при link). O(1). */
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
