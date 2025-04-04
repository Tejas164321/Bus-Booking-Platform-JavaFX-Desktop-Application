package models;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;

public class Bus {
    private final SimpleIntegerProperty busId;
    private final SimpleStringProperty busName;
    private final SimpleStringProperty source;
    private final SimpleStringProperty destination;
    private final SimpleStringProperty travelTime;
    private final SimpleIntegerProperty seats;
    private final SimpleDoubleProperty price;
    private final SimpleStringProperty date;
    private final SimpleIntegerProperty scheduleId;

    // ✅ Constructor for buses + schedules (for joined queries)
    public Bus(int busId, String busName, String source, String destination, String travelTime, int seats, double price, String date, int scheduleId) {
        this.busId = new SimpleIntegerProperty(busId);
        this.busName = new SimpleStringProperty(busName);
        this.source = new SimpleStringProperty(source);
        this.destination = new SimpleStringProperty(destination);
        this.travelTime = new SimpleStringProperty(travelTime);
        this.seats = new SimpleIntegerProperty(seats);
        this.price = new SimpleDoubleProperty(price);
        this.date = new SimpleStringProperty(date);
        this.scheduleId = new SimpleIntegerProperty(scheduleId);
    }

    // ✅ Overloaded constructor for AddBusController (no schedule details)
    public Bus(int busId, String busName, String source, String destination, String travelTime, int seats, double price, String date) {
        this(busId, busName, source, destination, travelTime, seats, price, date, 0); // Pass scheduleId = 0 by default
    }


    // ✅ Overloaded Constructor (For Simple Bus Data)
    public Bus(int busId, String busName, int seats, double price) {
        this(busId, busName, "", "", "", seats, price, "", 0); // Default values for missing fields
}


    // Getters

    // Setters
    public void setBusName(String busName) {
        this.busName.set(busName);
    }

    public void setSource(String source) {
        this.source.set(source);
    }

    public void setDestination(String destination) {
        this.destination.set(destination);
    }

    public void setTime(String travelTime) {
        this.travelTime.set(travelTime);
    }

    public void setSeats(int seats) {
        this.seats.set(seats);
    }

    public void setPrice(double price) {
        this.price.set(price);
    }

    
    

    public int getBusId() { return busId.get(); }
    public String getBusName() { return busName.get(); }
    public String getSource() { return source.get(); }
    public String getDestination() { return destination.get(); }
    public String getTravelTime() { return travelTime.get(); }
    public int getSeats() { return seats.get(); }
    public double getPrice() { return price.get(); }
    public String getDate() { return date.get(); }
    public int getScheduleId() { return scheduleId.get(); }

    // Property getters for JavaFX binding
    public SimpleIntegerProperty busIdProperty() { return busId; }
    public SimpleStringProperty busNameProperty() { return busName; }
    public SimpleStringProperty sourceProperty() { return source; }
    public SimpleStringProperty destinationProperty() { return destination; }
    public SimpleStringProperty travelTimeProperty() { return travelTime; }
    public SimpleIntegerProperty seatsProperty() { return seats; }
    public SimpleDoubleProperty priceProperty() { return price; }
    public SimpleStringProperty dateProperty() { return date; }
    public SimpleIntegerProperty scheduleIdProperty() { return scheduleId; }

    // Compatibility getter (in case you use getTime() in some places)
    public String getTime() { return travelTime.get(); }
}
