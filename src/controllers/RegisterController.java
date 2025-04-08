package controllers;

import database.DatabaseConnection;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.mindrot.jbcrypt.BCrypt;

public class RegisterController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private PasswordField confirmPasswordField;

    @FXML
    private Hyperlink goToLogin;

   @FXML
public void handleRegister(ActionEvent event) {
    String username = usernameField.getText().trim();
    String password = passwordField.getText().trim();
    String confirmPassword = confirmPasswordField.getText().trim();

    // Input validation
    if (username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
        showAlert("Error", "Please fill in all fields.");
        return;
    }

    if (!password.equals(confirmPassword)) {
        showAlert("Error", "Passwords do not match.");
        return;
    }

    // Password strength validation (optional)
    if (password.length() < 8) {
        showAlert("Error", "Password must be at least 8 characters long.");
        return;
    }

    try (Connection conn = DatabaseConnection.getConnection()) {
        // Check if username already exists
        String checkUserQuery = "SELECT username FROM users WHERE username = ?";
        PreparedStatement checkStmt = conn.prepareStatement(checkUserQuery);
        checkStmt.setString(1, username);
        ResultSet rs = checkStmt.executeQuery();

        if (rs.next()) {
            showAlert("Error", "Username already exists.");
            return;
        }

        // Hash the password before storing
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

        // Insert new user with hashed password
        String insertQuery = "INSERT INTO users (username, password, isAdmin) VALUES (?, ?, 0)";
        PreparedStatement pst = conn.prepareStatement(insertQuery);
        pst.setString(1, username);
        pst.setString(2, hashedPassword); // Store the HASHED password
        pst.executeUpdate();

        showAlert("Success", "Registration successful! Please login.");
        loadScene("/views/login.fxml");

    } catch (SQLException e) {
        showAlert("Database Error", "Registration failed: " + e.getMessage());
        e.printStackTrace();
    }
}

    @FXML
    public void goToLogin(ActionEvent event) {
        loadScene("/views/login.fxml");
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void loadScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setMaximized(true);
            stage.setScene(new Scene(root,1700,600));
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Could not load the page.");
        }
    }
}
