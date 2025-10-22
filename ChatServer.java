import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ChatServer {
    private static final int PORT = 12345;
    private static List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private static int clientIdCounter = 1;
    private static Connection dbConnection;

    public static void main(String[] args) {
        // Initialize database
        try {
            initDatabase();
        } catch (SQLException e) {
            System.out.println("Error initializing database: " + e.getMessage());
            return;
        }

        System.out.println("Chat Server starting on port " + PORT + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(() -> {
                    if (handler.authenticate()) {
                        clients.add(handler);
                        handler.start();
                    } else {
                        try {
                            clientSocket.close();
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    }
                }).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (dbConnection != null) dbConnection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private static void initDatabase() throws SQLException {
        dbConnection = DriverManager.getConnection("jdbc:sqlite:chat.db");
        Statement stmt = dbConnection.createStatement();
        // Create users table
        stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                     "username TEXT PRIMARY KEY, " +
                     "password_hash TEXT NOT NULL" +
                     ")");
        // Create chat_logs table
        stmt.execute("CREATE TABLE IF NOT EXISTS chat_logs (" +
                     "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                     "timestamp TEXT NOT NULL, " +
                     "sender_username TEXT NOT NULL, " +
                     "message TEXT NOT NULL" +
                     ")");
        stmt.close();
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    public static boolean registerUser(String username, String passwordHash) {
        try {
            PreparedStatement pstmt = dbConnection.prepareStatement(
                "INSERT OR IGNORE INTO users (username, password_hash) VALUES (?, ?)"
            );
            pstmt.setString(1, username);
            pstmt.setString(2, passwordHash);
            int rows = pstmt.executeUpdate();
            pstmt.close();
            return rows > 0;
        } catch (SQLException e) {
            System.out.println("Error registering user: " + e.getMessage());
            return false;
        }
    }

    public static String authenticateUser(String username, String passwordHash) {
        try {
            PreparedStatement pstmt = dbConnection.prepareStatement(
                "SELECT password_hash FROM users WHERE username = ?"
            );
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                if (storedHash.equals(passwordHash)) {
                    return username; // Successful auth
                }
            }
            rs.close();
            pstmt.close();
            return null;
        } catch (SQLException e) {
            System.out.println("Error authenticating user: " + e.getMessage());
            return null;
        }
    }

    public static void logMessage(String senderUsername, String message) {
        try {
            PreparedStatement pstmt = dbConnection.prepareStatement(
                "INSERT INTO chat_logs (timestamp, sender_username, message) VALUES (?, ?, ?)"
            );
            pstmt.setString(1, new Date().toString());
            pstmt.setString(2, senderUsername);
            pstmt.setString(3, message);
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            System.out.println("Error logging message: " + e.getMessage());
        }
    }

    // Broadcast message to all clients except the sender
    public static void broadcast(String message, ClientHandler sender) {
        String formattedMessage = sender.getUsername() + ": " + message;
        System.out.println(formattedMessage);
        logMessage(sender.getUsername(), message); // Log to DB
        for (ClientHandler client : clients) {
            if (client != sender) {
                client.sendMessage(formattedMessage);
            }
        }
    }

    // Remove a client when they disconnect
    public static void removeClient(ClientHandler handler) {
        clients.remove(handler);
        System.out.println(handler.getUsername() + " disconnected.");
    }
}

class ClientHandler extends Thread {
    private Socket socket;
    private String username;
    private PrintWriter out;
    private BufferedReader in;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getUsername() {
        return username;
    }

    public void sendMessage(String message) {
        out.println(message);
    }

    public boolean authenticate() {
        try {
            // Prompt for action: login or register
            sendMessage("Enter 'login' or 'register':");
            String action = in.readLine();
            if (action == null) return false;

            // Get username and password
            sendMessage("Enter username:");
            String usernameInput = in.readLine();
            sendMessage("Enter password:");
            String passwordInput = in.readLine();

            String passwordHash = ChatServer.hashPassword(passwordInput);

            if ("register".equalsIgnoreCase(action)) {
                if (ChatServer.registerUser(usernameInput, passwordHash)) {
                    sendMessage("Registration successful! You can now chat.");
                    this.username = usernameInput;
                    System.out.println("New user registered: " + username);
                    return true;
                } else {
                    sendMessage("Registration failed: Username may already exist.");
                    return false;
                }
            } else if ("login".equalsIgnoreCase(action)) {
                String authResult = ChatServer.authenticateUser(usernameInput, passwordHash);
                if (authResult != null) {
                    this.username = authResult;
                    sendMessage("Login successful! Welcome back, " + username + ".");
                    System.out.println("User logged in: " + username);
                    return true;
                } else {
                    sendMessage("Login failed: Invalid username or password.");
                    return false;
                }
            } else {
                sendMessage("Invalid action. Disconnecting.");
                return false;
            }
        } catch (IOException e) {
            System.out.println("Authentication error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void run() {
        try {
            // Welcome message after auth
            sendMessage("Type your messages below. Type 'exit' to quit.");

            String message;
            while ((message = in.readLine()) != null) {
                if ("exit".equalsIgnoreCase(message)) {
                    break;
                }
                ChatServer.broadcast(message, this);
            }
        } catch (IOException e) {
            System.out.println("Error with " + username + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            ChatServer.removeClient(this);
        }
    }
}