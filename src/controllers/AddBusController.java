package controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.collections.FXCollections;
import models.Bus;
import database.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;
import javafx.util.converter.IntegerStringConverter;
import javafx.util.converter.DoubleStringConverter;

public class AddBusController {
    @FXML private TableView<Bus> busTable;
    @FXML private TableColumn<Bus, Integer> idCol;
    @FXML private TableColumn<Bus, String> nameCol;
    @FXML private TableColumn<Bus, String> sourceCol;
    @FXML private TableColumn<Bus, String> destCol;
    @FXML private TableColumn<Bus, String> timeCol;
    @FXML private TableColumn<Bus, Integer> seatsCol;
    @FXML private TableColumn<Bus, Double> priceCol;
    @FXML private TableColumn<Bus, Void> editCol;

    @FXML private TextField busNameField;
    @FXML private TextField sourceField;
    @FXML private TextField destinationField;
    @FXML private TextField timeField;
    @FXML private TextField seatsField;
    @FXML private TextField priceField;

    @FXML
    public void initialize() {
        idCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().getBusId()).asObject());
        nameCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getBusName()));
        sourceCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getSource()));
        destCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getDestination()));
        timeCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getTime()));
        seatsCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().getSeats()).asObject());
        priceCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleDoubleProperty(cellData.getValue().getPrice()).asObject());

        busTable.setEditable(true);
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<>() {
            @Override
            public String toString(String object) {
                return object;
            }
            @Override
            public String fromString(String string) {
                return string;
            }
        }));
        sourceCol.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<>() {
            @Override
            public String toString(String object) {
                return object;
            }
            @Override
            public String fromString(String string) {
                return string;
            }
        }));
        destCol.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<>() {
            @Override
            public String toString(String object) {
                return object;
            }
            @Override
            public String fromString(String string) {
                return string;
            }
        }));
        timeCol.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<>() {
            @Override
            public String toString(String object) {
                return object;
            }
            @Override
            public String fromString(String string) {
                return string;
            }
        }));
        seatsCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        priceCol.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));

        nameCol.setOnEditCommit(event -> {
            Bus bus = event.getRowValue();
            bus.setBusName(event.getNewValue().trim());
            updateBusInDatabase(bus); 
        });
        sourceCol.setOnEditCommit(event -> {
            Bus bus = event.getRowValue();
            bus.setSource(event.getNewValue().trim());
            updateBusInDatabase(bus); 
        });
        destCol.setOnEditCommit(event -> {
            Bus bus = event.getRowValue();
            bus.setDestination(event.getNewValue().trim());
            updateBusInDatabase(bus); 
        });
        timeCol.setOnEditCommit(event -> {
            Bus bus = event.getRowValue();
            bus.setTime(event.getNewValue().trim());
            updateBusInDatabase(bus); 
        });
        seatsCol.setOnEditCommit(event -> {
            Bus bus = event.getRowValue();
            bus.setSeats(event.getNewValue()); 
            updateBusInDatabase(bus); // Directly set the Integer value
        });
        
        priceCol.setOnEditCommit(event -> {
            Bus bus = event.getRowValue();
            bus.setPrice(event.getNewValue()); 
            updateBusInDatabase(bus); // Directly set the Double value
        });
        

        loadBusData();
        addEditButtonToTable();
    }

    private void loadBusData() {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM buses");
             ResultSet rs = stmt.executeQuery()) {

            ObservableList<Bus> buses = FXCollections.observableArrayList();

            while (rs.next()) {
                buses.add(new Bus(
                    rs.getInt("bus_id"),
                    rs.getString("bus_name"),
                    rs.getString("source"),
                    rs.getString("destination"),
                    rs.getString("time"),
                    rs.getInt("seats"),
                    rs.getDouble("price"),
                    ""
                ));
            }
            busTable.setItems(buses);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    public void handleAddBus() {
        try {
            String busName = busNameField.getText().trim();
            String source = sourceField.getText().trim();
            String destination = destinationField.getText().trim();
            String time = timeField.getText().trim();
            String seatsStr = seatsField.getText().trim();
            String priceStr = priceField.getText().trim();

            if (busName.isEmpty() || source.isEmpty() || destination.isEmpty() || time.isEmpty()
                || seatsStr.isEmpty() || priceStr.isEmpty()) {
                System.out.println("Please fill in all fields.");
                return;
            }

            int seats = Integer.parseInt(seatsStr);
            double price = Double.parseDouble(priceStr);

            try (Connection conn = DatabaseConnection.getConnection()) {
                String sql = "INSERT INTO buses (bus_name, source, destination, time, seats, price) VALUES (?, ?, ?, ?, ?, ?)";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, busName);
                stmt.setString(2, source);
                stmt.setString(3, destination);
                stmt.setString(4, time);
                stmt.setInt(5, seats);
                stmt.setDouble(6, price);
                stmt.executeUpdate();
                System.out.println("Bus added successfully.");
            }

            busNameField.clear();
            sourceField.clear();
            destinationField.clear();
            timeField.clear();
            seatsField.clear();
            priceField.clear();

            loadBusData();

        } catch (NumberFormatException e) {
            System.out.println("Please enter valid numeric values for seats and price.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateBusInDatabase(Bus bus) {
        String sql = "UPDATE buses SET bus_name=?, source=?, destination=?, time=?, seats=?, price=? WHERE bus_id=?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bus.getBusName());
            ps.setString(2, bus.getSource());
            ps.setString(3, bus.getDestination());
            ps.setString(4, bus.getTime());
            ps.setInt(5, bus.getSeats());
            ps.setDouble(6, bus.getPrice());
            ps.setInt(7, bus.getBusId());
            ps.executeUpdate();
            showAlert("Success", "Bus details updated successfully!");
        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to update bus details.");
        }
    }

    private void addEditButtonToTable() {
        editCol.setCellFactory(col -> new TableCell<>() {
            private final Button editButton = new Button("✏️ Edit");

            {
                editButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-cursor: hand; -fx-background-radius: 5;");
                editButton.setOnMouseEntered(e -> editButton.setStyle("-fx-background-color: #45a049; -fx-text-fill: white; -fx-cursor: hand; -fx-background-radius: 5;"));
                editButton.setOnMouseExited(e -> editButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-cursor: hand; -fx-background-radius: 5;"));

                editButton.setOnAction(event -> {
                    Bus bus = getTableView().getItems().get(getIndex());
                    showEditPopup(bus);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    setGraphic(editButton);
                }
            }
        });
    }

  private void showEditPopup(Bus bus) {
    Dialog<Bus> dialog = new Dialog<>();
    dialog.setTitle("Edit Bus Details");
    dialog.setHeaderText("Edit details for Bus ID: " + bus.getBusId());

    // Set button types
    ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

    // Create fields
    TextField busNameField = new TextField(bus.getBusName());
    TextField sourceField = new TextField(bus.getSource());
    TextField destinationField = new TextField(bus.getDestination());
    TextField timeField = new TextField(bus.getTime());
    TextField seatsField = new TextField(String.valueOf(bus.getSeats()));
    TextField priceField = new TextField(String.valueOf(bus.getPrice()));

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);

    grid.add(new Label("Bus Name:"), 0, 0);
    grid.add(busNameField, 1, 0);
    grid.add(new Label("Source:"), 0, 1);
    grid.add(sourceField, 1, 1);
    grid.add(new Label("Destination:"), 0, 2);
    grid.add(destinationField, 1, 2);
    grid.add(new Label("Time:"), 0, 3);
    grid.add(timeField, 1, 3);
    grid.add(new Label("Seats:"), 0, 4);
    grid.add(seatsField, 1, 4);
    grid.add(new Label("Price:"), 0, 5);
    grid.add(priceField, 1, 5);

    dialog.getDialogPane().setContent(grid);

    // Convert results to updated Bus object
    dialog.setResultConverter(dialogButton -> {
        if (dialogButton == saveButtonType) {
            bus.setBusName(busNameField.getText().trim());
            bus.setSource(sourceField.getText().trim());
            bus.setDestination(destinationField.getText().trim());
            bus.setTime(timeField.getText().trim());
            bus.setSeats(Integer.parseInt(seatsField.getText().trim()));
            bus.setPrice(Double.parseDouble(priceField.getText().trim()));
            return bus;
        }
        return null;
    });

    dialog.showAndWait().ifPresent(updatedBus -> {
        updateBusInDatabase(updatedBus);
        loadBusData();
    });
}


    @FXML
    private void handleBack(ActionEvent event) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource("/views/admindashboard.fxml"));
            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            stage.setScene(new Scene(root, 1700, 900));
            stage.setMaximized(true);
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
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
