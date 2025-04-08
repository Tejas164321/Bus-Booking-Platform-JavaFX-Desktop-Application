package controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import models.Bus;
import java.io.IOException;
import java.sql.*;
import database.DatabaseConnection;
import javafx.scene.control.cell.PropertyValueFactory;

public class UserBookingController {

    @FXML private TextField fullNameField, ageField, sourceField, destinationField;
    @FXML private RadioButton maleRadio, femaleRadio;
    @FXML private DatePicker travelDatePicker;
    @FXML private TableView<Bus> busesTable;
    @FXML private TableColumn<Bus, String> colBusName, colBusType, colSource, colDestination, colTravelTime, colDate;
    @FXML private TableColumn<Bus, Integer> colSeatsAvailable;
    @FXML private TableColumn<Bus, Double> colPrice;
    @FXML private Label lblBusName, lblBusType, lblRoute, lblTravelTime, lblSeats, lblPrice, lblDate;
    @FXML private GridPane seatGrid;
    @FXML private Button btnConfirmBooking;

    private ToggleGroup genderGroup;
    private Connection conn;
    private Bus lastSelectedBus = null;
    private final int rows = 5, cols = 4;
    private ObservableList<Integer> bookedSeats = FXCollections.observableArrayList();
    private ObservableList<Integer> selectedSeats = FXCollections.observableArrayList();
    private int loggedInUserId;
    private String loggedInUsername;

    public UserBookingController() {
        conn = DatabaseConnection.getConnection();
    }

    @FXML
    public void initialize() {
        // Initialize gender radio buttons
        genderGroup = new ToggleGroup();
        maleRadio.setToggleGroup(genderGroup);
        femaleRadio.setToggleGroup(genderGroup);

        // Set table properties
        busesTable.setFixedCellSize(35);
        busesTable.setPrefHeight(180);
        busesTable.setPlaceholder(new Label("No buses found. Please search for available buses."));

        // Initialize table columns with PropertyValueFactory
        colBusName.setCellValueFactory(new PropertyValueFactory<>("busName"));
        colBusType.setCellValueFactory(new PropertyValueFactory<>("busType")); 
        colSource.setCellValueFactory(new PropertyValueFactory<>("source"));
        colDestination.setCellValueFactory(new PropertyValueFactory<>("destination"));
        colTravelTime.setCellValueFactory(new PropertyValueFactory<>("travelTime"));
        colSeatsAvailable.setCellValueFactory(new PropertyValueFactory<>("seats"));
        colPrice.setCellValueFactory(new PropertyValueFactory<>("price"));
        colDate.setCellValueFactory(new PropertyValueFactory<>("date"));

        // Add selection listener
        busesTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldSelection, newSelection) -> {
                if (newSelection != null) {
                    updateBusDetails(newSelection);
                }
            });
    }

    private void updateBusDetails(Bus bus) {
        lblBusName.setText(bus.getBusName());
        lblRoute.setText(bus.getSource() + " → " + bus.getDestination());
        lblTravelTime.setText(bus.getTravelTime());
        lblSeats.setText("Seats Available: " + bus.getSeats());
        lblPrice.setText("₹" + bus.getPrice());
        lblDate.setText("Date: " + bus.getDate());
        
        loadSeatLayout(bus.getBusId(), bus.getDate());
    }

    @FXML
    public void handleBusSelection(MouseEvent event) {
        if (event.getClickCount() == 1) {
            Bus selectedBus = busesTable.getSelectionModel().getSelectedItem();
            if (selectedBus != null) {
                updateBusDetails(selectedBus);
            }
        }
    }

    // [Rest of your methods remain exactly the same...]
    // searchBuses(), loadSeatLayout(), selectSeat(), confirmBooking(), 
    // isUserExists(), isSeatAlreadyBooked(), showAlert(), 
    // navigateToPayment(), handleBack() methods go here unchanged
    // ...
    // Modified setter method with validation
    public void setLoggedInUser(int userId, String username) {
        if (userId <= 0 || username == null || username.isEmpty()) {
            System.out.println("Invalid user data received!");
            showAlert("Session Error", "Please log in again.");
            return;
        }
        
        this.loggedInUserId = userId;
        this.loggedInUsername = username;
        System.out.println("Booking Controller Initialized - ID: " + userId 
                          + ", Username: " + username);
    }

    @FXML
    public void searchBuses(ActionEvent event) {
        String source = sourceField.getText().trim();
        String destination = destinationField.getText().trim();
        String travelDate = (travelDatePicker.getValue() != null) ? travelDatePicker.getValue().toString() : "";

        if (source.isEmpty() || destination.isEmpty() || travelDate.isEmpty()) {
            showAlert("Error", "Please enter all travel details!");
            return;
        }

        // Validate input to prevent SQL injection
        if (!source.matches("[a-zA-Z ]+") || !destination.matches("[a-zA-Z ]+")) {
            showAlert("Error", "Invalid characters in source or destination");
            return;
        }

        ObservableList<Bus> buses = FXCollections.observableArrayList();
        String query = "SELECT b.bus_id, b.bus_name, s.source, s.destination, s.travel_time, b.seats, b.price, s.travel_date " +
                       "FROM buses b JOIN bus_schedule s ON b.bus_id = s.bus_id " +
                       "WHERE s.source = ? AND s.destination = ? AND s.travel_date = ?";

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, source);
            stmt.setString(2, destination);
            stmt.setString(3, travelDate);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                buses.add(new Bus(
                    rs.getInt("bus_id"),
                    rs.getString("bus_name"),
                    rs.getString("source"),
                    rs.getString("destination"),
                    rs.getString("travel_time"),
                    rs.getInt("seats"),
                    rs.getDouble("price"),
                    rs.getString("travel_date")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        busesTable.setItems(buses);
    }
    private void loadSeatLayout(int busId, String travelDate) {
        seatGrid.getChildren().clear();
        bookedSeats.clear();
        selectedSeats.clear();
    
        String query = "SELECT seat_no FROM bookings WHERE bus_id = ? AND travel_date = ?";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, busId);
            stmt.setString(2, travelDate);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                bookedSeats.add(rs.getInt("seat_no"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    
        int seatNum = 1;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                Button seatButton = new Button(String.valueOf(seatNum));
                seatButton.getStyleClass().add("seat-button");
                
                // Set initial style based on seat availability
                if (bookedSeats.contains(seatNum)) {
                    seatButton.getStyleClass().add("booked");
                    seatButton.setDisable(true);
                } else {
                    seatButton.getStyleClass().add("available");
                    int finalSeatNum = seatNum;
                    seatButton.setOnAction(e -> selectSeat(finalSeatNum, seatButton));
                }
    
                seatGrid.add(seatButton, j, i);
                seatNum++;
            }
        }
    }

    private void selectSeat(int seatNum, Button seatButton) {
        if (selectedSeats.contains(seatNum)) {
            selectedSeats.remove((Integer) seatNum);
            seatButton.getStyleClass().remove("selected");
            seatButton.getStyleClass().add("available");
        } else {
            selectedSeats.add(seatNum);
            seatButton.getStyleClass().remove("available");
            seatButton.getStyleClass().add("selected");
        }
    }
    @FXML
    private void confirmBooking() {
        System.out.println("Confirm Booking Called");
        System.out.println("Current Logged In User ID: " + loggedInUserId);
        System.out.println("Current Logged In Username: " + loggedInUsername);

        if (loggedInUserId <= 0) {
            System.out.println("⚠️ Invalid User ID Detected: " + loggedInUserId);
            showAlert("Authentication Error", "Please log in before booking. (User ID not set)");
            return;
        }

        if (selectedSeats.isEmpty()) {
            showAlert("No Seats Selected", "Please select at least one seat.");
            return;
        }

        Bus selectedBus = busesTable.getSelectionModel().getSelectedItem();
        if (selectedBus == null) {
            showAlert("No Bus Selected", "Please select a bus first.");
            return;
        }

        String insertQuery = "INSERT INTO bookings (bus_id, user_id, seat_no, travel_date, price) VALUES (?, ?, ?, ?, ?)";

        try {
            if (!isUserExists(loggedInUserId)) {
                showAlert("Error", "Invalid user account. Please log in again.");
                return;
            }

            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                for (int seat : selectedSeats) {
                    if (isSeatAlreadyBooked(selectedBus.getBusId(), seat, selectedBus.getDate())) {
                        conn.rollback();
                        showAlert("Booking Error", "Seat " + seat + " is already booked.");
                        return;
                    }

                    stmt.setInt(1, selectedBus.getBusId());
                    stmt.setInt(2, loggedInUserId);
                    stmt.setInt(3, seat);
                    stmt.setString(4, selectedBus.getDate());
                    stmt.setDouble(5, selectedBus.getPrice());
                    stmt.executeUpdate();
                }

                conn.commit();
                showAlert("Booking Confirmed", "Your seats have been booked successfully.");
                navigateToPayment(selectedBus);
            }
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                rollbackEx.printStackTrace();
            }
            e.printStackTrace();
            showAlert("Booking Error", "Failed to complete booking: " + e.getMessage());
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isUserExists(int userId) throws SQLException {
        String checkUserQuery = "SELECT COUNT(*) FROM users WHERE user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(checkUserQuery)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    private boolean isSeatAlreadyBooked(int busId, int seatNo, String travelDate) throws SQLException {
        String checkSeatQuery = "SELECT COUNT(*) FROM bookings WHERE bus_id = ? AND seat_no = ? AND travel_date = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(checkSeatQuery)) {
            pstmt.setInt(1, busId);
            pstmt.setInt(2, seatNo);
            pstmt.setString(3, travelDate);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void navigateToPayment(Bus bus) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/payment.fxml"));
            Parent root = loader.load();
            PaymentController paymentController = loader.getController();
            paymentController.setBookingDetails(bus, selectedSeats);

            Stage stage = new Stage();
            stage.setTitle("Payment");
            stage.setScene(new Scene(root));
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleBack(ActionEvent event) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource("/views/userDashboard.fxml"));
            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            stage.setScene(new Scene(root, 1500, 900));
            stage.setMaximized(true);
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}