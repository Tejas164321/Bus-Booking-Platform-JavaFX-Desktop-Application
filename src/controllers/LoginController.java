package controllers;

import database.DatabaseConnection;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;


public class LoginController {
    
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Hyperlink goToRegister;

    @FXML
    public void handleLogin(ActionEvent event) {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showAlert("Input Error", "Please fill in all fields.");
            return;
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT user_id, isAdmin FROM users WHERE username = ? AND password = ?";
            PreparedStatement pst = conn.prepareStatement(query);
            pst.setString(1, username);
            pst.setString(2, password);
            
            ResultSet rs = pst.executeQuery();
            if (rs.next()) {
                int userId = rs.getInt("user_id");
                boolean isAdmin = rs.getBoolean("isAdmin");

                if (isAdmin) {
                    loadDashboard("/views/adminDashboard.fxml", userId, username);
                } else {
                    loadDashboard("/views/userDashboard.fxml", userId, username);
                }
            } else {
                showAlert("Error", "Invalid username or password.");
            }
        } catch (Exception e) {
            showAlert("Database Error", "Login failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadDashboard(String fxmlPath, int userId, String username) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent root = loader.load();
            
            // Pass user data to dashboard controller
            if (fxmlPath.contains("userDashboard")) {
                UserController controller = loader.getController();
                controller.setUserData(userId, username);
            }

            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setScene(new Scene(root,1500,900));
            stage.setMaximized(true);
        } catch (Exception e) {
            showAlert("Error", "Cannot load dashboard: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    public void goToRegister(ActionEvent event) {
        loadScene("/views/register.fxml");
    }

    private void loadScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setScene(new Scene(root,1500,900));
            stage.setMaximized(true);
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