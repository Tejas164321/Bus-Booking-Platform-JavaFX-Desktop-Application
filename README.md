# Online-Bus-Booking-Platform

## Prerequisites
- **Java 21 JDK** installed (javac and java in PATH)
- **JavaFX SDK** bundled at `lib/javafx-sdk-21.0.6`
- **MySQL Connector/J** at `lib/mysql-connector-j-9.2.0/mysql-connector-j-9.2.0.jar`
- **jbcrypt** at `lib/jbcrypt-0.4.jar`
- A running **MySQL** server with a database named `bus_booking`

## Setup
1. Update database credentials in `src/database/DatabaseConnection.java` if needed.
2. Import schema and seed data if you have any SQL: `bus_booking.sql`.

## Build
```bash
./compile.sh
```

## Run
```bash
./run.sh
```

The app starts at the login screen (`views/login.fxml`).

## Notes
- If you are on Wayland or a headless server, JavaFX may require additional flags or a virtual display.
- To change window size, edit `src/Main.java`.