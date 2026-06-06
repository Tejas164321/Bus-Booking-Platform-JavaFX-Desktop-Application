# 🚍 Online Bus Booking Platform

[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.oracle.com/java/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21.0.6-blue)](https://openjfx.io/)
[![MySQL](https://img.shields.io/badge/MySQL-9.2.0-blue)](https://www.mysql.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A modern, user-friendly desktop application for booking bus tickets, built with JavaFX. This platform allows users to search for buses, select seats, and make bookings, while administrators can manage bus schedules and operations efficiently.

## 📋 Table of Contents

- [🚀 Overview](#-overview)
- [✨ Features](#-features)
- [🛠️ Technologies Used](#️-technologies-used)
- [📋 Prerequisites](#-prerequisites)
- [⚙️ Installation](#️-installation)
- [🗄️ Database Setup](#️-database-setup)
- [📖 Usage](#-usage)
- [🏗️ Project Structure](#️-project-structure)
- [🤝 Contributing](#-contributing)
- [📄 License](#-license)

## 🚀 Overview

The Online Bus Booking Platform is a comprehensive desktop application designed to streamline the bus ticket booking process. It features a dual-role system with separate interfaces for administrators and regular users, ensuring secure and efficient operations.

**Key Highlights:**
- 🔐 Secure user authentication with password hashing
- 👨‍💼 Admin dashboard for bus and schedule management
- 👤 User-friendly booking interface with interactive seat selection
- 📊 Real-time bus availability and filtering
- 💳 Integrated payment processing
- 🎨 Modern UI with custom styling

## ✨ Features

### 👨‍💼 Admin Features
- **Bus Management**: Add, view, and delete bus information
- **Schedule Management**: Create and manage bus schedules with dates and times
- **Dashboard Overview**: Comprehensive table view of all buses and schedules
- **Filtering Options**: Filter buses by date (Today, Tomorrow, Onward)
- **Auto-Cleanup**: Automatic deletion of expired schedules

### 👤 User Features
- **User Registration & Login**: Secure account creation and authentication
- **Bus Search**: Search buses by source, destination, and travel date
- **Interactive Seat Selection**: Visual seat layout with real-time availability
- **Booking Management**: Confirm bookings and view details
- **Payment Integration**: Seamless payment processing for confirmed bookings

### 🔒 Security Features
- Password hashing using BCrypt
- SQL injection prevention with prepared statements
- User session management
- Role-based access control (Admin/User)

## 🛠️ Technologies Used

- **Language**: Java 21
- **GUI Framework**: JavaFX 21.0.6
- **Database**: MySQL 9.2.0
- **Database Connector**: MySQL Connector/J
- **Password Hashing**: jBCrypt 0.4
- **Build Tool**: Manual compilation (javac)
- **Styling**: CSS for JavaFX components

## 📋 Prerequisites

Before running this application, ensure you have the following installed:

- **Java Development Kit (JDK) 21** or higher
- **MySQL Server** 8.0 or higher
- **JavaFX SDK** 21.0.6 (included in the project)
- **Git** for cloning the repository

## ⚙️ Installation

1. **Clone the Repository**
   ```bash
   git clone https://github.com/your-username/online-bus-booking-platform.git
   cd online-bus-booking-platform
   ```

2. **Set Up JavaFX**
   - JavaFX SDK is already included in the `lib/` directory
   - No additional setup required

3. **Compile the Application**
   ```bash
   # Compile all Java files
   javac -cp "lib/*" -d bin src/**/*.java

   # Or use the provided compile script if available
   ```

4. **Set Up Environment Variables** (Optional)
   - Ensure `JAVA_HOME` points to your JDK 21 installation
   - Add JavaFX bin directory to PATH if needed

## 🗄️ Database Setup

1. **Create MySQL Database**
   ```sql
   CREATE DATABASE bus_booking;
   ```

2. **Run the SQL Script**
   - Execute the `bus_booking.sql` file in your MySQL client
   - This will create all necessary tables: `users`, `buses`, `bus_schedule`, `bookings`

3. **Update Database Configuration**
   - Open `src/database/DatabaseConnection.java`
   - Update the database URL, username, and password as per your MySQL setup

   ```java
   private static final String URL = "jdbc:mysql://localhost:3306/bus_booking";
   private static final String USER = "your_mysql_username";
   private static final String PASSWORD = "your_mysql_password";
   ```

## 📖 Usage

### 🚀 Running the Application

```bash
# Run the application
java -cp "bin:lib/*" --module-path lib/javafx-sdk-21.0.6/lib --add-modules javafx.controls,javafx.fxml Main
```

### 👤 User Workflow

1. **Launch Application**: Start the app and you'll see the login screen
2. **Register/Login**: Create a new account or log in with existing credentials
3. **Search Buses**: Enter source, destination, and travel date
4. **Select Bus**: Choose from available options
5. **Pick Seats**: Click on available seats in the interactive layout
6. **Confirm Booking**: Review details and confirm your booking
7. **Payment**: Complete the payment process

### 👨‍💼 Admin Workflow

1. **Login**: Use admin credentials to access the admin dashboard
2. **Manage Buses**: Add new buses or delete existing ones
3. **Manage Schedules**: Create schedules for buses with specific dates and times
4. **View Dashboard**: Monitor all buses and schedules with filtering options
5. **Auto Cleanup**: System automatically removes expired schedules

## 🏗️ Project Structure

```
online-bus-booking-platform/
│
├── src/
│   ├── Main.java                           # Application entry point
│   ├── controllers/                        # JavaFX controllers
│   │   ├── LoginController.java
│   │   ├── RegisterController.java
│   │   ├── AdminController.java
│   │   ├── UserController.java
│   │   ├── UserBookingController.java
│   │   ├── PaymentController.java
│   │   ├── AddBusController.java
│   │   └── AddScheduleController.java
│   ├── models/                             # Data models
│   │   ├── Bus.java
│   │   └── BusSchedule.java
│   ├── database/                           # Database connection
│   │   └── DatabaseConnection.java
│   └── views/                              # FXML view files
│       ├── login.fxml
│       ├── register.fxml
│       ├── adminDashboard.fxml
│       ├── userDashboard.fxml
│       ├── userBooking.fxml
│       ├── payment.fxml
│       ├── addBus.fxml
│       └── addSchedule.fxml
│
├── lib/                                    # External libraries
│   ├── javafx-sdk-21.0.6/
│   ├── mysql-connector-j-9.2.0.jar
│   └── jbcrypt-0.4.jar
│
├── bin/                                    # Compiled classes and resources
│   ├── Main.class
│   ├── controllers/
│   ├── models/
│   ├── database/
│   ├── views/
│   ├── styles/
│   ├── images/
│   └── lib/
│
├── bus_booking.sql                         # Database schema
├── styles/                                 # CSS stylesheets
│   ├── login.css
│   ├── booking.css
│   └── userDashboard.css
├── images/                                 # UI images
│   ├── bus-logo.png
│   └── logout.png
└── README.md                               # Project documentation
```

## 🤝 Contributing

We welcome contributions to improve the Online Bus Booking Platform! Here's how you can help:

1. **Fork the Repository**
2. **Create a Feature Branch**
   ```bash
   git checkout -b feature/AmazingFeature
   ```
3. **Commit Your Changes**
   ```bash
   git commit -m 'Add some AmazingFeature'
   ```
4. **Push to the Branch**
   ```bash
   git push origin feature/AmazingFeature
   ```
5. **Open a Pull Request**

### Development Guidelines
- Follow Java naming conventions
- Add comments for complex logic
- Test your changes thoroughly
- Update documentation as needed

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

**Happy Traveling!** 🎉

For questions or support, please open an issue on GitHub.
