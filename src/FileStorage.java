import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class FileStorage implements AutoCloseable {
    private static final int HEADER_SIZE = 8;
    private static final int NAME_SIZE = 20;
    private static final int DESCRIPTION_SIZE = 200;
    private static final int DATE_SIZE = 8;
    private static final int TIME_SIZE = 5;
    private static final int TYPE_SIZE = 30;
    private static final int RECORD_SIZE =
            4 + NAME_SIZE + DESCRIPTION_SIZE + DATE_SIZE
                    + TIME_SIZE * 2 + TYPE_SIZE + 1 + 1;

    private final RandomAccessFile file; //файл бд
    private final HashIndex[] indexes = new HashIndex[Field.values().length];
    private int slotCount; //сколько слотов занята
    private int nextId; //какой ид выдавать след записи

    //формирует путь для каждого индекса
    private static String indexPath(String path, Field field) {
        return path + "." + field.name().toLowerCase() + ".idx";
    }

    //создание пустой базы
    public static void create(String path) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path, "rw")) {
            f.setLength(0);
            f.writeInt(0);
            f.writeInt(1);
        }

        for (Field field : Field.values()) {
            HashIndex.create(indexPath(path, field));
        }
    }

    //открывает базу
    public FileStorage(String path) throws IOException {
        file = new RandomAccessFile(path, "rw");
        //проверка чтобы значения существовали или были адекватными
        if (file.length() < HEADER_SIZE) {
            file.close();
            throw new IOException("Некорректный файл базы данных");
        }

        file.seek(0);
        slotCount = file.readInt();
        nextId = file.readInt();

        if (slotCount < 0 || nextId < 1) {
            file.close();
            throw new IOException("Повреждён заголовок базы данных");
        }

        boolean rebuild = false;
        try { //проверяем файл индекса (если его нет/короче - rebuild и создание пустого)
            for (Field field : Field.values()) {
                String p = indexPath(path, field);
                File idx = new File(p);
                if (!idx.exists() || idx.length() < HashIndex.minSize()) {
                    HashIndex.create(p);
                    rebuild = true;
                }
                indexes[field.ordinal()] = new HashIndex(p);
            }
            if (rebuild) rebuildIndexes();
        } catch (IOException | RuntimeException e) { //если что-то упало то все закрывае и выдаем ошибку
            closeIndexes();
            file.close();
            throw e;
        }
    }

    //закрывает осн файл и все индексы
    @Override
    public void close() throws IOException {
        IOException error = null;
        try {
            file.close();
        } catch (IOException e) {
            error = e;
        }

        for (HashIndex index : indexes) {
            if (index != null) {
                try {
                    index.close();
                } catch (IOException e) {
                    if (error == null) error = e;
                }
            }
        }
        if (error != null) throw error;
    }

    //закрывает индексы, игнорит ошибки (аварийная очистка конструктора)
    private void closeIndexes() {
        for (HashIndex index : indexes) {
            if (index != null) {
                try {
                    index.close();
                } catch (IOException ignored) { }
            }
        }
    }

    //позиция в байтах
    private long position(int slot) {
        return HEADER_SIZE + (long) slot * RECORD_SIZE;
    }
    //slotCount и nextId в начале
    private void writeHeader() throws IOException {
        file.seek(0);
        file.writeInt(slotCount);
        file.writeInt(nextId);
    }

    //строки записываются в поля фикс длины
    private byte[] stringBytes(String value, int size) throws IOException {
        if (value == null) value = "";
        byte[] source = value.getBytes(StandardCharsets.UTF_8);
        if (source.length > size) {
            throw new IOException("Слишком длинное значение: " + value);
        }
        byte[] result = new byte[size];
        System.arraycopy(source, 0, result, 0, source.length);
        return result;
    }

    //из строк в байты
    private String readString(ByteBuffer buffer, int size) {
        byte[] bytes = new byte[size];
        buffer.get(bytes);
        int length = 0;
        while (length < size && bytes[length] != 0) length++;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    //превращаем Event в массив 274 байт
    private byte[] encode(Event e, int id) throws IOException {
        if (e.date == null) throw new IOException("Не задана дата");

        ByteBuffer b = ByteBuffer.allocate(RECORD_SIZE);
        b.putInt(id);
        b.put(stringBytes(e.name, NAME_SIZE));
        b.put(stringBytes(e.description, DESCRIPTION_SIZE));
        b.putLong(e.date.toEpochDay());
        b.put(stringBytes(e.start_time, TIME_SIZE));
        b.put(stringBytes(e.end_time, TIME_SIZE));
        b.put(stringBytes(e.type, TYPE_SIZE));
        b.put(e.status);
        b.put((byte) 0); // запись не удалена
        return b.array();
    }

    //получение зн поля из Event
    private static Object key(Field field, Event e) {
        switch (field) {
            case ID: return e.id;
            case NAME: return e.name == null ? "" : e.name;
            case DESCRIPTION: return e.description == null ? "" : e.description;
            case DATE: return e.date;
            case START_TIME: return e.start_time == null ? "" : e.start_time;
            case END_TIME: return e.end_time == null ? "" : e.end_time;
            case TYPE: return e.type == null ? "" : e.type;
            case STATUS: return e.status;
            default: throw new IllegalArgumentException("Неизвестное поле: " + field);
        }
    }

    //преобразование теста поиска в тип поля
    private static Object parse(Field field, String value) {
        try {
            switch (field) {
                case ID: return Integer.parseInt(value.trim());
                case DATE: return LocalDate.parse(value.trim());
                case STATUS: return Byte.parseByte(value.trim());
                default: return value;
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Неверное значение для поля " + field);
        }
    }

    //чтение записи по номеру слота
    public Event read(int slot) throws IOException {
        if (slot < 0 || slot >= slotCount) return null;

        byte[] bytes = new byte[RECORD_SIZE];
        file.seek(position(slot));
        file.readFully(bytes);
        ByteBuffer b = ByteBuffer.wrap(bytes);

        int id = b.getInt();
        String name = readString(b, NAME_SIZE);
        String description = readString(b, DESCRIPTION_SIZE);
        LocalDate date = LocalDate.ofEpochDay(b.getLong());
        String startTime = readString(b, TIME_SIZE);
        String endTime = readString(b, TIME_SIZE);
        String type = readString(b, TYPE_SIZE);
        byte status = b.get();
        byte deleted = b.get();

        if (deleted == 1) return null;
        return new Event(id, name, description, date,
                startTime, endTime, type, status);
    }

    //собирает все не удаленные записи
    public List<Event> listAll() throws IOException {
        List<Event> result = new ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            Event e = read(i);
            if (e != null) result.add(e);
        }
        return result;
    }

    //поиск слотов в цепи хэш-индекса
    private void collect(Field field, Object value,
                         List<Integer> slots, List<Event> events) throws IOException {
        HashIndex index = indexes[field.ordinal()];
        for (int slot = index.head(value); slot != HashIndex.NIL; slot = index.next(slot)) {
            Event e = read(slot);
            if (e != null && value.equals(key(field, e))) {
                if (slots != null) slots.add(slot);
                if (events != null) events.add(e);
            }
        }
    }

    //ищем ивент по какому то параметру
    public List<Event> search(Field field, String value) throws IOException {
        List<Event> result = new ArrayList<>();
        collect(field, parse(field, value), null, result);
        result.sort((a, b) -> Integer.compare(a.id, b.id));
        return result;
    }

    //добавление записи в конец файла
    public int add(Event e) throws IOException {
        int id = e.id > 0 ? e.id : nextId;

        List<Integer> found = new ArrayList<>();
        collect(Field.ID, id, found, null);
        if (!found.isEmpty()) throw new IOException("Такой ID уже существует");

        byte[] record = encode(e, id);
        int slot = slotCount;

        file.seek(position(slot));
        file.write(record);
        slotCount++;
        if (id >= nextId) nextId = id + 1;
        writeHeader();
        e.id = id;

        for (Field field : Field.values()) {
            indexes[field.ordinal()].link(slot, key(field, e));
        }
        return id;
    }

    //мягкое удаление (запись остается в файле, но пользователь не видит её)
    //удаление одной записи
    private void removeSlot(int slot, Event e) throws IOException {
        for (Field field : Field.values()) {
            indexes[field.ordinal()].unlink(slot, key(field, e));
        }
        file.seek(position(slot) + RECORD_SIZE - 1);
        file.writeByte(1);
    }

    //удаление записей по определенному признаку (в визуале не реализовано)
    public int deleteBy(Field field, String value) throws IOException {
        List<Integer> slots = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        collect(field, parse(field, value), slots, events);

        for (int i = 0; i < slots.size(); i++) {
            removeSlot(slots.get(i), events.get(i));
        }
        return slots.size();
    }

    //обновление записи и ид
    public boolean update(Event e) throws IOException {
        List<Integer> slots = new ArrayList<>();
        List<Event> oldEvents = new ArrayList<>();
        collect(Field.ID, e.id, slots, oldEvents);
        if (slots.isEmpty()) return false;

        int slot = slots.get(0);
        Event old = oldEvents.get(0);
        byte[] record = encode(e, e.id);

        file.seek(position(slot));
        file.write(record);

        for (Field field : Field.values()) {
            Object oldValue = key(field, old);
            Object newValue = key(field, e);
            if (!oldValue.equals(newValue)) {
                indexes[field.ordinal()].unlink(slot, oldValue);
                indexes[field.ordinal()].link(slot, newValue);
            }
        }
        return true;
    }

    //ресетает индексы в случае потере данных
    public void rebuildIndexes() throws IOException {
        for (HashIndex index : indexes) index.reset();

        for (int slot = 0; slot < slotCount; slot++) {
            Event e = read(slot);
            if (e != null) {
                for (Field field : Field.values()) {
                    indexes[field.ordinal()].link(slot, key(field, e));
                }
            }
        }
    }

    //считает живые записи
    public int getCount() throws IOException {
        int count = 0;
        for (int i = 0; i < slotCount; i++) {
            if (read(i) != null) count++;
        }
        return count;
    }

    //все слоты включая удаленные
    public int getSlotCount() {
        return slotCount;
    }
}
