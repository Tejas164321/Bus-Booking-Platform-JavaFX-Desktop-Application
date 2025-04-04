package controllers;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.scene.Node;

public class UserController {

    private int userId;
    private String username;

    // Method to receive data from LoginController
    public void setUserData(int userId, String username) {
        this.userId = userId;
        this.username = username;
        System.out.println("User Dashboard Initialized - ID: " + userId + ", Username: " + username);
    }

    @FXML
    private void handleStartJourney(ActionEvent event) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/userBooking.fxml"));
            Parent root = loader.load();
            
            // Pass user data to Booking Controller
            UserBookingController bookingController = loader.getController();
            bookingController.setLoggedInUser(userId, username);

            System.out.println("Booking Controller Initialized - ID: " + userId 
                          + ", Username: " + username);
            
            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            stage.setScene(new Scene(root,1600,900));
            stage.setMaximized(true);
            
        } catch (Exception e) {
            showAlert("Navigation Error", "Could not load booking page: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void handleLogout(ActionEvent event) {
        System.out.println("Logout initiated by User ID: " + userId);
        loadScene("/views/login.fxml", event);
        showAlert("Logout Successful", "Goodbye, " + username + "!");
    }

    private void loadScene(String fxmlPath, ActionEvent event) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            stage.setScene(new Scene(root,1600,900));
            stage.setMaximized(true);
        } catch (Exception e) {
            showAlert("Error", "Could not load page: " + e.getMessage());
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