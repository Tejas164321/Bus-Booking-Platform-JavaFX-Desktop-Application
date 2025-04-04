package models;

import javafx.beans.property.*;
import java.time.LocalDate;
import java.time.LocalTime;

public class BusSchedule {
    private final SimpleIntegerProperty scheduleId;
    private final SimpleIntegerProperty busId;
    private final SimpleStringProperty busName;
    private final ObjectProperty<LocalDate> travelDate;
    private final ObjectProperty<LocalTime> travelTime;
    private final SimpleStringProperty source;
    private final SimpleStringProperty destination;
    private final SimpleDoubleProperty price;
    private final SimpleIntegerProperty availableSeats;

    public BusSchedule(int scheduleId, int busId, String busName, LocalDate travelDate, LocalTime travelTime,
                       String source, String destination, double price, int availableSeats) {
        this.scheduleId = new SimpleIntegerProperty(scheduleId);
        this.busId = new SimpleIntegerProperty(busId);
        this.busName = new SimpleStringProperty(busName);
        this.travelDate = new SimpleObjectProperty<>(travelDate);
        this.travelTime = new SimpleObjectProperty<>(travelTime);
        this.source = new SimpleStringProperty(source);
        this.destination = new SimpleStringProperty(destination);
        this.price = new SimpleDoubleProperty(price);
        this.availableSeats = new SimpleIntegerProperty(availableSeats);
    }

    // Getters
    public int getScheduleId() { return scheduleId.get(); }
    public int getBusId() { return busId.get(); }
    public String getBusName() { return busName.get(); }
    public LocalDate getTravelDate() { return travelDate.get(); }
    public LocalTime getTravelTime() { return travelTime.get(); }
    public String getSource() { return source.get(); }
    public String getDestination() { return destination.get(); }
    public double getPrice() { return price.get(); }
    public int getAvailableSeats() { return availableSeats.get(); }

    // Setters
    public void setScheduleId(int scheduleId) { this.scheduleId.set(scheduleId); }
    public void setBusId(int busId) { this.busId.set(busId); }
    public void setBusName(String busName) { this.busName.set(busName); }
    public void setTravelDate(LocalDate travelDate) { this.travelDate.set(travelDate); }
    public void setTravelTime(LocalTime travelTime) { this.travelTime.set(travelTime); }
    public void setSource(String source) { this.source.set(source); }
    public void setDestination(String destination) { this.destination.set(destination); }
    public void setPrice(double price) { this.price.set(price); }
    public void setAvailableSeats(int availableSeats) { this.availableSeats.set(availableSeats); }

    // Property getters for binding
    public SimpleIntegerProperty scheduleIdProperty() { return scheduleId; }
    public SimpleIntegerProperty busIdProperty() { return busId; }
    public SimpleStringProperty busNameProperty() { return busName; }
    public ObjectProperty<LocalDate> travelDateProperty() { return travelDate; }
    public ObjectProperty<LocalTime> travelTimeProperty() { return travelTime; }
    public SimpleStringProperty sourceProperty() { return source; }
    public SimpleStringProperty destinationProperty() { return destination; }
    public SimpleDoubleProperty priceProperty() { return price; }
    public SimpleIntegerProperty availableSeatsProperty() { return availableSeats; }
}