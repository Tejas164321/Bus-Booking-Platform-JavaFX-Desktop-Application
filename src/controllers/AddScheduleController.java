package controllers;

import database.DatabaseConnection;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.util.converter.DoubleStringConverter;
import javafx.util.converter.IntegerStringConverter;
import models.BusSchedule;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javafx.util.StringConverter;

public class AddScheduleController {

    @FXML private TextField busIdField, busNameField, travelTimeField, sourceField, destinationField, priceField, availableSeatsField;
    @FXML private DatePicker travelDatePicker;
    @FXML private TableView<BusSchedule> scheduleTable;
    @FXML private TableColumn<BusSchedule, Integer> scheduleIdCol, busIdCol, availableSeatsCol;
    @FXML private TableColumn<BusSchedule, String> busNameCol, dateCol, timeCol, sourceCol, destinationCol;
    @FXML private TableColumn<BusSchedule, Double> priceCol;

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    @FXML
    public void initialize() {
        configureTableColumns();
        setupInlineEditing();
        loadSchedules();
        setupBusIdListener();
        addEditButtonToTable();
    }

    private void configureTableColumns() {
        scheduleIdCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getScheduleId()).asObject());
        busIdCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getBusId()).asObject());
        busNameCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getBusName()));
        dateCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTravelDate().toString()));
        timeCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTravelTime().format(timeFormatter)));
        sourceCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getSource()));
        destinationCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getDestination()));
        priceCol.setCellValueFactory(cellData -> new SimpleDoubleProperty(cellData.getValue().getPrice()).asObject());
        availableSeatsCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getAvailableSeats()).asObject());
    }

    private void setupInlineEditing() {
        scheduleTable.setEditable(true);

        StringConverter<String> stringConverter = new StringConverter<>() {
            @Override public String toString(String s) { return s; }
            @Override public String fromString(String s) { return s; }
        };

        busNameCol.setCellFactory(TextFieldTableCell.forTableColumn(stringConverter));
        busNameCol.setOnEditCommit(event -> updateScheduleField(event.getRowValue(), "bus_name", event.getNewValue()));

        sourceCol.setCellFactory(TextFieldTableCell.forTableColumn(stringConverter));
        sourceCol.setOnEditCommit(event -> updateScheduleField(event.getRowValue(), "source", event.getNewValue()));

        destinationCol.setCellFactory(TextFieldTableCell.forTableColumn(stringConverter));
        destinationCol.setOnEditCommit(event -> updateScheduleField(event.getRowValue(), "destination", event.getNewValue()));

        priceCol.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        priceCol.setOnEditCommit(event -> updateScheduleField(event.getRowValue(), "price", event.getNewValue().toString()));

        availableSeatsCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        availableSeatsCol.setOnEditCommit(event -> updateScheduleField(event.getRowValue(), "available_seats", event.getNewValue().toString()));
    }

    private void addEditButtonToTable() {
        TableColumn<BusSchedule, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(100);

        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button editButton = new Button("✏️ Edit");

            {
                editButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-cursor: hand;");
                editButton.setOnMouseEntered(e -> editButton.setStyle("-fx-background-color: #45a049;"));
                editButton.setOnMouseExited(e -> editButton.setStyle("-fx-background-color: #4CAF50;"));
                editButton.setOnAction(event -> showEditDialog(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : editButton);
            }
        });

        scheduleTable.getColumns().add(actionCol);
    }

    private void updateScheduleField(BusSchedule schedule, String field, String newValue) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String sql = "UPDATE bus_schedule SET " + field + " = ? WHERE schedule_id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            
            switch (field) {
                case "price":
                    stmt.setDouble(1, Double.parseDouble(newValue));
                    break;
                case "available_seats":
                    stmt.setInt(1, Integer.parseInt(newValue));
                    break;
                default:
                    stmt.setString(1, newValue);
            }
            
            stmt.setInt(2, schedule.getScheduleId());
            stmt.executeUpdate();
            loadSchedules();
        } catch (Exception e) {
            showAlert("Error", "Invalid value for " + field.replace("_", " "));
        }
    }

    @SuppressWarnings("unused")
    private void showEditDialog(BusSchedule schedule) {
        Dialog<BusSchedule> dialog = new Dialog<>();
        dialog.setTitle("Edit Schedule");
        dialog.setHeaderText("Editing Schedule ID: " + schedule.getScheduleId());

        DatePicker datePicker = new DatePicker(schedule.getTravelDate());
        TextField timeField = new TextField(schedule.getTravelTime().format(timeFormatter));
        TextField sourceField = new TextField(schedule.getSource());
        TextField destField = new TextField(schedule.getDestination());
        TextField priceField = new TextField(String.valueOf(schedule.getPrice()));
        TextField seatsField = new TextField(String.valueOf(schedule.getAvailableSeats()));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Travel Date:"), datePicker);
        grid.addRow(1, new Label("Travel Time (HH:mm):"), timeField);
        grid.addRow(2, new Label("Source:"), sourceField);
        grid.addRow(3, new Label("Destination:"), destField);
        grid.addRow(4, new Label("Price:"), priceField);
        grid.addRow(5, new Label("Available Seats:"), seatsField);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                try {
                    schedule.setTravelDate(datePicker.getValue());
                    schedule.setTravelTime(LocalTime.parse(timeField.getText(), timeFormatter));
                    schedule.setSource(sourceField.getText());
                    schedule.setDestination(destField.getText());
                    schedule.setPrice(Double.parseDouble(priceField.getText()));
                    schedule.setAvailableSeats(Integer.parseInt(seatsField.getText()));
                    return schedule;
                } catch (DateTimeParseException | NumberFormatException e) {
                    showAlert("Invalid Input", "Please check time format (HH:mm) and numeric fields");
                    return null;
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(updatedSchedule -> {
            updateFullSchedule(updatedSchedule);
            loadSchedules();
        });
    }

    private void updateFullSchedule(BusSchedule schedule) {
        String sql = "UPDATE bus_schedule SET travel_date = ?, travel_time = ?, source = ?, " +
                     "destination = ?, price = ?, available_seats = ? WHERE schedule_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setDate(1, Date.valueOf(schedule.getTravelDate()));
            ps.setTime(2, Time.valueOf(schedule.getTravelTime()));
            ps.setString(3, schedule.getSource());
            ps.setString(4, schedule.getDestination());
            ps.setDouble(5, schedule.getPrice());
            ps.setInt(6, schedule.getAvailableSeats());
            ps.setInt(7, schedule.getScheduleId());
            
            ps.executeUpdate();
            showAlert("Success", "Schedule updated successfully!");
        } catch (SQLException e) {
            showAlert("Error", "Failed to update schedule: " + e.getMessage());
        }
    }

    private void loadSchedules() {
        ObservableList<BusSchedule> schedules = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM bus_schedule")) {
            
            while (rs.next()) {
                schedules.add(new BusSchedule(
                    rs.getInt("schedule_id"),
                    rs.getInt("bus_id"),
                    rs.getString("bus_name"),
                    rs.getDate("travel_date").toLocalDate(),
                    rs.getTime("travel_time").toLocalTime(),
                    rs.getString("source"),
                    rs.getString("destination"),
                    rs.getDouble("price"),
                    rs.getInt("available_seats")
                ));
            }
            scheduleTable.setItems(schedules);
        } catch (SQLException e) {
            showAlert("Error", "Failed to load schedules: " + e.getMessage());
        }
    }

    private void setupBusIdListener() {
        busIdField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.isEmpty() && newValue.matches("\\d+")) {
                String busName = fetchBusName(Integer.parseInt(newValue));
                busNameField.setText(busName != null ? busName : "");
            } else {
                busNameField.setText("");
            }
        });
    }

    private String fetchBusName(int busId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT bus_name FROM buses WHERE bus_id = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, busId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getString("bus_name") : null;
        } catch (SQLException e) {
            showAlert("Error", "Failed to fetch bus name: " + e.getMessage());
            return null;
        }
    }

    @FXML
    private void handleAddSchedule(ActionEvent event) {
        try {
            int busId = Integer.parseInt(busIdField.getText().trim());
            LocalDate travelDate = travelDatePicker.getValue();
            String travelTime = travelTimeField.getText().trim();
            String source = sourceField.getText().trim();
            String destination = destinationField.getText().trim();
            double price = Double.parseDouble(priceField.getText().trim());
            int availableSeats = Integer.parseInt(availableSeatsField.getText().trim());

            if (travelDate == null || travelTime.isEmpty() || source.isEmpty() || destination.isEmpty()) {
                showAlert("Error", "Please fill all required fields");
                return;
            }

            if (!busExists(busId)) {
                showAlert("Error", "Bus ID " + busId + " does not exist");
                return;
            }

            try (Connection conn = DatabaseConnection.getConnection()) {
                String sql = "INSERT INTO bus_schedule (bus_id, bus_name, travel_date, travel_time, source, " +
                             "destination, price, available_seats) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                PreparedStatement stmt = conn.prepareStatement(sql);
                
                stmt.setInt(1, busId);
                stmt.setString(2, fetchBusName(busId));
                stmt.setDate(3, Date.valueOf(travelDate));
                stmt.setTime(4, Time.valueOf(LocalTime.parse(travelTime, timeFormatter)));
                stmt.setString(5, source);
                stmt.setString(6, destination);
                stmt.setDouble(7, price);
                stmt.setInt(8, availableSeats);
                
                stmt.executeUpdate();
                showAlert("Success", "Schedule added successfully");
                clearFields();
                loadSchedules();
            }
        } catch (DateTimeParseException e) {
            showAlert("Invalid Time", "Please enter time in HH:mm format");
        } catch (NumberFormatException e) {
            showAlert("Invalid Input", "Please check numeric fields (Price, Seats)");
        } catch (SQLException e) {
            showAlert("Database Error", "Failed to add schedule: " + e.getMessage());
        }
    }

    private boolean busExists(int busId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT COUNT(*) FROM buses WHERE bus_id = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, busId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            showAlert("Error", "Failed to verify bus existence");
            return false;
        }
    }

    private void clearFields() {
        busIdField.clear();
        busNameField.clear();
        travelDatePicker.setValue(null);
        travelTimeField.clear();
        sourceField.clear();
        destinationField.clear();
        priceField.clear();
        availableSeatsField.clear();
    }

    @FXML
    private void handleBack(ActionEvent event) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource("/views/adminDashboard.fxml"));
            Stage stage = (Stage) scheduleTable.getScene().getWindow();
            stage.setScene(new Scene(root, 1700, 900));
            stage.setMaximized(true);
        } catch (Exception e) {
            showAlert("Error", "Failed to load dashboard: " + e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}