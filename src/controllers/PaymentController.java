package controllers;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import models.Bus;
import javafx.collections.ObservableList;

public class PaymentController {

    @FXML private Label lblBusDetails;
    @FXML private Label lblSeats;
    @FXML private Label lblTotalAmount;
    @FXML private RadioButton rbUPI;
    @FXML private RadioButton rbCard;
    @FXML private VBox upiForm;
    @FXML private VBox cardForm;
    @FXML private TextField txtUPIId;
    @FXML private TextField txtCardNumber;
    @FXML private TextField txtExpiry;
    @FXML private TextField txtCVV;

    private Bus selectedBus;
    private ObservableList<Integer> selectedSeats;
    private double totalAmount;

    public void setBookingDetails(Bus bus, ObservableList<Integer> seats) {
        this.selectedBus = bus;
        this.selectedSeats = seats;
        updateUI();
    }

    private void updateUI() {
        // Set bus details
        lblBusDetails.setText(selectedBus.getBusName() + " - " + 
                            selectedBus.getSource() + " to " + 
                            selectedBus.getDestination());
        
        // Set seat numbers
        lblSeats.setText(selectedSeats.toString());
        
        // Calculate total amount
        totalAmount = selectedSeats.size() * selectedBus.getPrice();
        lblTotalAmount.setText(String.format("₹%.2f", totalAmount));
    }

    @FXML
    private void initialize() {
        // Setup payment method visibility
        rbUPI.selectedProperty().addListener((obs, oldVal, newVal) -> {
            upiForm.setVisible(newVal);
            cardForm.setVisible(false);
        });

        rbCard.selectedProperty().addListener((obs, oldVal, newVal) -> {
            cardForm.setVisible(newVal);
            upiForm.setVisible(false);
        });
    }

    @FXML
    private void processPayment() {
        if (!validatePaymentDetails()) {
            showAlert("Validation Error", "Please fill all required payment details");
            return;
        }

        if (rbUPI.isSelected()) {
            processUPIPayment();
        } else if (rbCard.isSelected()) {
            processCardPayment();
        }

        showAlert("Payment Successful", "Your payment of ₹" + totalAmount + " was successful!");
        // Add database update logic here
    }

    private boolean validatePaymentDetails() {
        if (rbUPI.isSelected()) {
            return !txtUPIId.getText().trim().isEmpty();
        }
        if (rbCard.isSelected()) {
            return !txtCardNumber.getText().trim().isEmpty() &&
                   !txtExpiry.getText().trim().isEmpty() &&
                   !txtCVV.getText().trim().isEmpty();
        }
        return false;
    }

    private void processUPIPayment() {
        String upiId = txtUPIId.getText().trim();
        // Add UPI payment processing logic
        System.out.println("Processing UPI payment for: " + upiId);
    }

    private void processCardPayment() {
        String cardNumber = txtCardNumber.getText().trim();
        String expiry = txtExpiry.getText().trim();
        String cvv = txtCVV.getText().trim();
        // Add card payment processing logic
        System.out.println("Processing card payment - Card: " + 
                          maskCardNumber(cardNumber) + 
                          " Exp: " + expiry);
    }

    private String maskCardNumber(String cardNumber) {
        if (cardNumber.length() > 4) {
            return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
        }
        return cardNumber;
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}