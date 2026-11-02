import java.time.LocalDate;

public class Event {
    public int id;
    public String name;
    public String description;
    public LocalDate date;
    public String start_time;
    public String end_time;
    public String type;
    public byte status;

    public Event(int id, String name, String description, LocalDate date,
                 String start_time, String end_time, String type, byte status) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.date = date;
        this.start_time = start_time;
        this.end_time = end_time;
        this.type = type;
        this.status = status;
    }
}
