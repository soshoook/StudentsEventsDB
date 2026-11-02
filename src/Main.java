import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;

public class Main extends JFrame {
    private static final String DB_PATH = "events.db";
    private static final String BACKUP_DIR = "events_backup";

    private FileStorage storage; //текущая открытая бд
    private final JTable table; //таблица в окне
    private final DefaultTableModel model; //её модель данных

    public Main() {
        setTitle("База данных мероприятий");
        setSize(1050, 550);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        String[] columns = {
                "ID", "Название", "Описание", "Дата",
                "Время", "Начало", "Конец", "Тип", "Статус"
        };

        model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout());
        addButton(buttons, "Создать / очистить", this::createDatabase);
        addButton(buttons, "Открыть", this::openDatabase);
        addButton(buttons, "Добавить", this::addEvent);
        addButton(buttons, "Изменить", this::editEvent);
        addButton(buttons, "Удалить", this::deleteEvent);
        addButton(buttons, "Поиск", this::searchEvents);
        addButton(buttons, "Показать все", this::refreshTable);
        addButton(buttons, "Резервная копия", this::backupDatabase);
        addButton(buttons, "Восстановить", this::restoreDatabase);

        add(buttons, BorderLayout.NORTH);

        openDatabase();
    }

    //создание кнопки
    private void addButton(JPanel panel, String title, Runnable action) {
        JButton button = new JButton(title);
        button.addActionListener(e -> action.run());
        panel.add(button);
    }

    //диалоговое окно с ошибкой
    private void showError(Exception e) {
        JOptionPane.showMessageDialog(this, e.getMessage(),
                "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    //закрывает базу если открыта
    private void closeStorage() throws IOException {
        if (storage != null) {
            storage.close();
            storage = null;
        }
    }

    //закрывает старую базу и открывает уже готовую (либо сам создает новую)
    private void openDatabase() {
        try {
            closeStorage();

            File db = new File(DB_PATH);
            if (!db.exists()) {
                FileStorage.create(DB_PATH);
            }

            storage = new FileStorage(DB_PATH);
            refreshTable();
        } catch (Exception e) {
            showError(e);
        }
    }

    //создает новую пустую базу
    private void createDatabase() {
        int answer = JOptionPane.showConfirmDialog(this,
                "Создать пустую базу? Текущие записи будут удалены.",
                "Подтверждение", JOptionPane.YES_NO_OPTION);

        if (answer != JOptionPane.YES_OPTION) return;

        try {
            closeStorage();
            FileStorage.create(DB_PATH);
            storage = new FileStorage(DB_PATH);
            refreshTable();
        } catch (Exception e) {
            showError(e);
        }
    }

    //диалоговое окно для создание ивента
    private Event eventFromDialog(Event old) {
        //текстовые поля
        JTextField name = new JTextField(old == null ? "" : old.name);
        JTextField description = new JTextField(old == null ? "" : old.description);
        JTextField date = new JTextField(old == null ? "2026-11-05" : old.date.toString());
        JTextField start = new JTextField(old == null ? "" : old.start_time);
        JTextField end = new JTextField(old == null ? "" : old.end_time);
        JTextField type = new JTextField(old == null ? "" : old.type);
        JTextField status = new JTextField(old == null ? "0" : Byte.toString(old.status));

        //раскладываем данные по колонкам
        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel("Название:")); panel.add(name);
        panel.add(new JLabel("Описание:")); panel.add(description);
        panel.add(new JLabel("Дата (ГГГГ-ММ-ДД):")); panel.add(date);
        panel.add(new JLabel("Время начала:")); panel.add(start);
        panel.add(new JLabel("Время окончания:")); panel.add(end);
        panel.add(new JLabel("Тип:")); panel.add(type);
        panel.add(new JLabel("Статус (0 - активно, 1 - завершено):")); panel.add(status);

        int result = JOptionPane.showConfirmDialog(this, panel,
                old == null ? "Новое мероприятие" : "Изменение мероприятия",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return null;

        //парсит дату, чтобы был верный формат
        LocalDate parsedDate = LocalDate.parse(date.getText().trim());
        byte parsedStatus = Byte.parseByte(status.getText().trim());
        int id = old == null ? 0 : old.id;

        return new Event(id, name.getText(), description.getText(),
                parsedDate, start.getText(), end.getText(),
                type.getText(), parsedStatus);
    }

    //создание ивента
    private void addEvent() {
        if (storage == null) {
            showError(new IOException("Сначала откройте базу данных"));
            return;
        }

        try {
            Event event = eventFromDialog(null);
            if (event == null) return;

            int id = storage.add(event);
            refreshTable();
            JOptionPane.showMessageDialog(this, "Мероприятие добавлено. ID = " + id);
        } catch (Exception e) {
            showError(e);
        }
    }

    //какое мероприятие выделено в таблице (для изм и удаления)
    private Event selectedEvent() {
        int row = table.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Сначала выберите запись в таблице");
            return null;
        }

        int id = (int) model.getValueAt(row, 0);
        try {
            List<Event> found = storage.search(Field.ID, Integer.toString(id));
            return found.isEmpty() ? null : found.get(0);
        } catch (IOException e) {
            showError(e);
            return null;
        }
    }

    //изменение данных меро
    private void editEvent() {
        Event old = selectedEvent();
        if (old == null) return;

        try {
            Event changed = eventFromDialog(old);
            if (changed == null) return;

            if (storage.update(changed)) {
                refreshTable();
                JOptionPane.showMessageDialog(this, "Запись изменена");
            }
        } catch (Exception e) {
            showError(e);
        }
    }

    //получает выбранный ивент, подтверждает удаление
    private void deleteEvent() {
        Event event = selectedEvent();
        if (event == null) return;

        int answer = JOptionPane.showConfirmDialog(this,
                "Удалить мероприятие «" + event.name + "»?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);

        if (answer != JOptionPane.YES_OPTION) return;

        try {
            storage.deleteBy(Field.ID, Integer.toString(event.id));
            refreshTable();
        } catch (Exception e) {
            showError(e);
        }
    }

    //ищем мероприятия по ключам
    private void searchEvents() {
        if (storage == null) return;

        Field[] fields = Field.values();
        Field field = (Field) JOptionPane.showInputDialog(this,
                "По какому полю искать?", "Поиск",
                JOptionPane.QUESTION_MESSAGE, null, fields, Field.NAME);

        if (field == null) return;

        String value = JOptionPane.showInputDialog(this, "Введите значение:");
        if (value == null) return;

        try {
            showEvents(storage.search(field, value));
        } catch (Exception e) {
            showError(e);
        }
    }

    private void refreshTable() {
        if (storage == null) return;

        try {
            showEvents(storage.listAll());
        } catch (Exception e) {
            showError(e);
        }
    }

    //показывает меро в таблице
    private void showEvents(List<Event> events) {
        model.setRowCount(0);

        for (Event e : events) {
            model.addRow(new Object[]{
                    e.id, e.name, e.description, e.date, e.start_time, e.end_time, e.type, e.status
            });
        }
    }

    //бэкап
    private void backupDatabase() {
        if (storage == null) return;

        try {
            closeStorage();//закрываем бд, чтобы все данные точно были на диске
            //создает копию бд в отдельную папку вместе с индексами
            Path backup = Path.of(BACKUP_DIR);
            Files.createDirectories(backup);

            copyFile(Path.of(DB_PATH), backup.resolve(DB_PATH));
            for (Field field : Field.values()) {
                String indexName = DB_PATH + "." + field.name().toLowerCase() + ".idx";
                Path index = Path.of(indexName);
                if (Files.exists(index)) {
                    copyFile(index, backup.resolve(indexName));
                }
            }

            storage = new FileStorage(DB_PATH);
            JOptionPane.showMessageDialog(this, "Резервная копия создана");
        } catch (Exception e) {
            try { storage = new FileStorage(DB_PATH); } catch (Exception ignored) { }
            showError(e);
        }
    }

    private void restoreDatabase() {
        Path backup = Path.of(BACKUP_DIR);

        if (!Files.exists(backup.resolve(DB_PATH))) {
            showError(new IOException("Резервная копия не найдена"));
            return;
        }

        int answer = JOptionPane.showConfirmDialog(this,
                "Заменить текущую базу резервной копией?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);

        if (answer != JOptionPane.YES_OPTION) return;

        try {
            closeStorage();
            copyFile(backup.resolve(DB_PATH), Path.of(DB_PATH));

            for (Field field : Field.values()) {
                String indexName = DB_PATH + "." + field.name().toLowerCase() + ".idx";
                Path source = backup.resolve(indexName);
                if (Files.exists(source)) {
                    copyFile(source, Path.of(indexName));
                }
            }

            storage = new FileStorage(DB_PATH);
            refreshTable();
            JOptionPane.showMessageDialog(this, "База восстановлена");
        } catch (Exception e) {
            showError(e);
        }
    }

    private void copyFile(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().setVisible(true));
    }
}
