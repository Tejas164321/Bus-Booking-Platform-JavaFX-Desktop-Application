package controllers;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import models.Bus;
import database.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

public class AdminController {

    @FXML private TableView<Bus> busTable;
    @FXML private TableColumn<Bus, Integer> idCol;
    @FXML private TableColumn<Bus, String> nameCol;
    @FXML private TableColumn<Bus, String> sourceCol;
    @FXML private TableColumn<Bus, String> destCol;
    @FXML private TableColumn<Bus, String> timeCol;
    @FXML private TableColumn<Bus, Integer> seatsCol;
    @FXML private TableColumn<Bus, Double> priceCol;
    @FXML private TableColumn<Bus, String> dateCol;
    @FXML private ComboBox<String> dateFilterCombo;

    @FXML
    public void initialize() {
        idCol.setCellValueFactory(cellData -> cellData.getValue().busIdProperty().asObject());
        nameCol.setCellValueFactory(cellData -> cellData.getValue().busNameProperty());
        sourceCol.setCellValueFactory(cellData -> cellData.getValue().sourceProperty());
        destCol.setCellValueFactory(cellData -> cellData.getValue().destinationProperty());
        timeCol.setCellValueFactory(cellData -> cellData.getValue().travelTimeProperty()); // ✅ changed here
        seatsCol.setCellValueFactory(cellData -> cellData.getValue().seatsProperty().asObject());
        priceCol.setCellValueFactory(cellData -> cellData.getValue().priceProperty().asObject());
        dateCol.setCellValueFactory(cellData -> cellData.getValue().dateProperty());

        dateFilterCombo.getItems().addAll("All", "Today", "Tomorrow", "Onward");
        dateFilterCombo.setValue("All");
        dateFilterCombo.setOnAction(e -> handleRefreshBusList());

        deleteOldSchedules(); // ✅ automatically delete old schedules
        handleRefreshBusList();
    }

    @FXML
    private void handleAddBus(ActionEvent event) {
        loadScene(event, "/views/addBus.fxml");
    }

    @FXML
    private void handleDeleteBus() {
        Bus selectedBus = busTable.getSelectionModel().getSelectedItem();
        if (selectedBus == null) {
            showAlert("Error", "Please select a bus to delete.");
            return;
        }

        if (!confirmAction("Are you sure you want to delete this bus? This will delete the bus and all related schedules.")) return;

        try (Connection conn = DatabaseConnection.getConnection()) {
            PreparedStatement deleteSchedules = conn.prepareStatement("DELETE FROM bus_schedule WHERE bus_id = ?");
            deleteSchedules.setInt(1, selectedBus.getBusId());
            deleteSchedules.executeUpdate();

            PreparedStatement deleteBus = conn.prepareStatement("DELETE FROM buses WHERE bus_id = ?");
            deleteBus.setInt(1, selectedBus.getBusId());
            deleteBus.executeUpdate();

            showAlert("Success", "Bus and related schedules deleted successfully!");
            handleRefreshBusList();
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Failed to delete bus.");
        }
    }

    @FXML
    private void handleAddSchedule(ActionEvent event) {
        loadScene(event, "/views/addSchedule.fxml");
    }

    @FXML
    private void handleDeleteSchedule() {
        Bus selectedBus = busTable.getSelectionModel().getSelectedItem();
        if (selectedBus == null) {
            showAlert("Error", "Please select a schedule to delete.");
            return;
        }
    
        boolean confirm = confirmAction("Are you sure you want to delete this schedule?");
        if (!confirm) return;
    
        try (Connection conn = DatabaseConnection.getConnection()) {
            PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM bus_schedule WHERE schedule_id = ?");
            deleteStmt.setInt(1, selectedBus.getScheduleId());
            deleteStmt.executeUpdate();
    
            showAlert("Success", "Schedule deleted successfully!");
            handleRefreshBusList();  // Refresh table after deletion
    
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Failed to delete the schedule.");
        }
    }
    
    

    @FXML
    private void handleLogout(ActionEvent event) {
        loadScene(event, "/views/login.fxml");
    }

    // ✅ Automatically delete old bus schedules where date < today
    private void deleteOldSchedules() {
        try (Connection conn = DatabaseConnection.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM bus_schedule WHERE travel_date < ?");
            stmt.setString(1, LocalDate.now().toString());
            int deletedRows = stmt.executeUpdate();
            if (deletedRows > 0) {
                System.out.println(deletedRows + " old schedules deleted from database.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Failed to delete old schedules.");
        }
    }
    @FXML
    private void handleRefreshBusList() {
        String selectedFilter = dateFilterCombo.getSelectionModel().getSelectedItem();
        LocalDate today = LocalDate.now();
        LocalDate filterDate = null;
    
        String query = "SELECT b.bus_id, b.bus_name, b.source, b.destination, bs.travel_time, b.seats, bs.price, bs.travel_date, bs.schedule_id " +
                       "FROM buses b JOIN bus_schedule bs ON b.bus_id = bs.bus_id ";
    
        switch (selectedFilter) {
            case "Today":
                query += "WHERE bs.travel_date = ?";
                filterDate = today;
                break;
            case "Tomorrow":
                query += "WHERE bs.travel_date = ?";
                filterDate = today.plusDays(1);
                break;
            case "Onward":
                query += "WHERE bs.travel_date > ?";
                filterDate = today;
                break;
            default:
                break;
        }
    
        try (Connection conn = DatabaseConnection.getConnection()) {
            PreparedStatement stmt;
    
            if (filterDate != null) {
                stmt = conn.prepareStatement(query);
                stmt.setString(1, filterDate.toString());
            } else {
                stmt = conn.prepareStatement(query.trim());
            }
    
            ResultSet rs = stmt.executeQuery();
            ObservableList<Bus> buses = FXCollections.observableArrayList();
            while (rs.next()) {
                buses.add(new Bus(
                    rs.getInt("bus_id"),
                    rs.getString("bus_name"),
                    rs.getString("source"),
                    rs.getString("destination"),
                    rs.getString("travel_time"),
                    rs.getInt("seats"),
                    rs.getDouble("price"),
                    rs.getString("travel_date"),
                    rs.getInt("schedule_id")
                ));
            }
            busTable.setItems(buses);
    
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Failed to load bus list.");
        }
    }
    

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private boolean confirmAction(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirmation");
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().filter(response -> response == ButtonType.OK).isPresent();
    }

    private void loadScene(ActionEvent event, String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            stage.setScene(new Scene(root, 1700, 900));
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Could not load the page.");
        }
    }
}
