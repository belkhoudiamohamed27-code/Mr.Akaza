package com.mohservice;

import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:moh_service.db";
    public static final double DZD_EXCHANGE_RATE = 260.0;

    public static class User {
        public long chatId;
        public String username;
        public String firstName;
        public double balance;
        public String createdAt;

        public User(long chatId, String username, String firstName, double balance, String createdAt) {
            this.chatId = chatId;
            this.username = username;
            this.firstName = firstName;
            this.balance = balance;
            this.createdAt = createdAt;
        }
    }

    public static class Product {
        public int id;
        public String category;
        public String name;
        public double price;
        public String imagePath;
        public String customEmojiId;
        public List<Offer> offers = new ArrayList<>();

        public Product(int id, String category, String name, double price, String imagePath, String customEmojiId) {
            this.id = id;
            this.category = category;
            this.name = name;
            this.price = price;
            this.imagePath = imagePath;
            this.customEmojiId = customEmojiId != null ? customEmojiId : "";
        }
    }

    public static class Offer {
        public int id;
        public int productId;
        public String productName;
        public String name;
        public double price;
        public String description;
        public boolean isAvailable;
        public int availableStock;
        public String sourceBots;
        public String sourceProductBtn;
        public String sourceOfferKeyword;
        public double profitMargin;
        public double lastSourcePrice;
        public boolean autoSync;

        public Offer(int id, int productId, String productName, String name, double price, String description,
                     boolean isAvailable, int availableStock, String sourceBots, String sourceProductBtn,
                     String sourceOfferKeyword, double profitMargin, double lastSourcePrice, boolean autoSync) {
            this.id = id;
            this.productId = productId;
            this.productName = productName;
            this.name = name;
            this.price = price;
            this.description = description;
            this.isAvailable = isAvailable;
            this.availableStock = availableStock;
            this.sourceBots = sourceBots != null ? sourceBots : "";
            this.sourceProductBtn = sourceProductBtn != null ? sourceProductBtn : "";
            this.sourceOfferKeyword = sourceOfferKeyword != null ? sourceOfferKeyword : "";
            this.profitMargin = profitMargin;
            this.lastSourcePrice = lastSourcePrice;
            this.autoSync = autoSync;
        }
    }

    public static class WatcherConfig {
        public String apiId;
        public String apiHash;
        public String phone;
        public String sessionString;
        public boolean isConnected;
        public int intervalMinutes;
        public String lastSyncAt;
        public String lastSyncLog;

        public WatcherConfig(String apiId, String apiHash, String phone, String sessionString,
                             boolean isConnected, int intervalMinutes, String lastSyncAt, String lastSyncLog) {
            this.apiId = apiId != null ? apiId : "";
            this.apiHash = apiHash != null ? apiHash : "";
            this.phone = phone != null ? phone : "";
            this.sessionString = sessionString != null ? sessionString : "";
            this.isConnected = isConnected;
            this.intervalMinutes = intervalMinutes > 0 ? intervalMinutes : 15;
            this.lastSyncAt = lastSyncAt != null ? lastSyncAt : "لم يتم الفحص بعد";
            this.lastSyncLog = lastSyncLog != null ? lastSyncLog : "";
        }
    }

    public static class PaymentMethod {
        public int id;
        public String name;
        public String currency; // "USD" or "DZD"
        public String iconPath;
        public String customEmojiId;
        public String instructions;
        public boolean isActive;

        public PaymentMethod(int id, String name, String currency, String iconPath, String customEmojiId, String instructions, boolean isActive) {
            this.id = id;
            this.name = name;
            this.currency = currency;
            this.iconPath = iconPath != null ? iconPath : "";
            this.customEmojiId = customEmojiId != null ? customEmojiId : "";
            this.instructions = instructions != null ? instructions : "";
            this.isActive = isActive;
        }
    }

    public static class TopupRequest {
        public int id;
        public long userId;
        public String userName;
        public String currency; // "USD" or "DZD"
        public double amountPaid; // Amount in selected currency
        public double amountUsd;  // Equivalent in USD to credit wallet
        public int paymentMethodId;
        public String paymentMethodName;
        public String proofText;
        public String proofImage;
        public String status; // WAITING_PROOF, PENDING_ADMIN, APPROVED, REJECTED, EXPIRED
        public String rejectionReason;
        public String createdAt;

        public TopupRequest(int id, long userId, String userName, String currency, double amountPaid, double amountUsd,
                            int paymentMethodId, String paymentMethodName, String proofText, String proofImage,
                            String status, String rejectionReason, String createdAt) {
            this.id = id;
            this.userId = userId;
            this.userName = userName;
            this.currency = currency;
            this.amountPaid = amountPaid;
            this.amountUsd = amountUsd;
            this.paymentMethodId = paymentMethodId;
            this.paymentMethodName = paymentMethodName;
            this.proofText = proofText;
            this.proofImage = proofImage;
            this.status = status;
            this.rejectionReason = rejectionReason;
            this.createdAt = createdAt;
        }
    }

    public static class Order {
        public int id;
        public long userId;
        public String userName;
        public int productId;
        public int offerId;
        public String productName;
        public double price; // Always USD price
        public String paymentType; // "WALLET" or "DIRECT"
        public String currency; // "USD" or "DZD"
        public double amountToPay; // In selected currency
        public String paymentMethodName;
        public String proofText;
        public String proofImage;
        public String accountData;
        public String status; // WAITING_PROOF, PENDING, COMPLETED, REJECTED, EXPIRED
        public String rejectionReason;
        public String createdAt;

        public Order(int id, long userId, String userName, int productId, int offerId, String productName, double price,
                     String paymentType, String currency, double amountToPay, String paymentMethodName,
                     String proofText, String proofImage, String accountData, String status, String rejectionReason, String createdAt) {
            this.id = id;
            this.userId = userId;
            this.userName = userName;
            this.productId = productId;
            this.offerId = offerId;
            this.productName = productName;
            this.price = price;
            this.paymentType = paymentType != null ? paymentType : "WALLET";
            this.currency = currency != null ? currency : "USD";
            this.amountToPay = amountToPay > 0 ? amountToPay : price;
            this.paymentMethodName = paymentMethodName != null ? paymentMethodName : "المحفظة";
            this.proofText = proofText;
            this.proofImage = proofImage;
            this.accountData = accountData;
            this.status = status;
            this.rejectionReason = rejectionReason;
            this.createdAt = createdAt;
        }
    }

    public static class PurchaseResult {
        public enum Status {
            INSUFFICIENT_BALANCE,
            OFFER_NOT_FOUND,
            OFFER_UNAVAILABLE,
            DELIVERED,
            PENDING,
            ERROR
        }

        public Status status;
        public Order order;
        public double userBalance;
        public double offerPrice;

        public PurchaseResult(Status status, Order order, double userBalance, double offerPrice) {
            this.status = status;
            this.order = order;
            this.userBalance = userBalance;
            this.offerPrice = offerPrice;
        }
    }

    public DatabaseManager() {
        initDatabase();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private void initDatabase() {
        String createUsers = """
                CREATE TABLE IF NOT EXISTS users (
                    chat_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    balance REAL DEFAULT 0.0,
                    created_at TEXT
                );
                """;

        String createProducts = """
                CREATE TABLE IF NOT EXISTS products (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT DEFAULT '1',
                    name TEXT NOT NULL,
                    price REAL DEFAULT 0.0,
                    image_path TEXT DEFAULT '',
                    custom_emoji_id TEXT DEFAULT ''
                );
                """;

        String createOffers = """
                CREATE TABLE IF NOT EXISTS offers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    price REAL NOT NULL,
                    description TEXT DEFAULT '',
                    is_available INTEGER DEFAULT 1,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """;

        String createStock = """
                CREATE TABLE IF NOT EXISTS stock (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    offer_id INTEGER DEFAULT 0,
                    account_data TEXT NOT NULL,
                    is_sold INTEGER DEFAULT 0,
                    FOREIGN KEY (product_id) REFERENCES products(id)
                );
                """;

        String createOrders = """
                CREATE TABLE IF NOT EXISTS orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    product_id INTEGER NOT NULL,
                    offer_id INTEGER DEFAULT 0,
                    product_name TEXT NOT NULL,
                    price REAL NOT NULL,
                    payment_type TEXT DEFAULT 'WALLET',
                    currency TEXT DEFAULT 'USD',
                    amount_to_pay REAL DEFAULT 0.0,
                    payment_method_name TEXT DEFAULT 'المحفظة',
                    proof_text TEXT DEFAULT '',
                    proof_image TEXT DEFAULT '',
                    account_data TEXT,
                    status TEXT NOT NULL,
                    rejection_reason TEXT DEFAULT '',
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(chat_id),
                    FOREIGN KEY (product_id) REFERENCES products(id)
                );
                """;

        String createPaymentMethods = """
                CREATE TABLE IF NOT EXISTS payment_methods (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    currency TEXT NOT NULL,
                    icon_path TEXT DEFAULT '',
                    custom_emoji_id TEXT DEFAULT '',
                    instructions TEXT NOT NULL,
                    is_active INTEGER DEFAULT 1
                );
                """;

        String createTopupRequests = """
                CREATE TABLE IF NOT EXISTS topup_requests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    currency TEXT NOT NULL,
                    amount_paid REAL NOT NULL,
                    amount_usd REAL NOT NULL,
                    payment_method_id INTEGER NOT NULL,
                    payment_method_name TEXT NOT NULL,
                    proof_text TEXT DEFAULT '',
                    proof_image TEXT DEFAULT '',
                    status TEXT NOT NULL,
                    rejection_reason TEXT DEFAULT '',
                    created_at TEXT NOT NULL
                );
                """;

        String createNotifications = """
                CREATE TABLE IF NOT EXISTS availability_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    offer_id INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE(user_id, offer_id)
                );
                """;

        String createWatcherConfig = """
                CREATE TABLE IF NOT EXISTS watcher_config (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    api_id TEXT DEFAULT '',
                    api_hash TEXT DEFAULT '',
                    phone TEXT DEFAULT '',
                    session_string TEXT DEFAULT '',
                    is_connected INTEGER DEFAULT 0,
                    interval_minutes INTEGER DEFAULT 15,
                    last_sync_at TEXT DEFAULT '',
                    last_sync_log TEXT DEFAULT ''
                );
                """;

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createUsers);
            stmt.execute(createProducts);
            stmt.execute(createOffers);
            stmt.execute(createStock);
            stmt.execute(createOrders);
            stmt.execute(createPaymentMethods);
            stmt.execute(createTopupRequests);
            stmt.execute(createNotifications);
            stmt.execute(createWatcherConfig);
            stmt.execute("INSERT OR IGNORE INTO watcher_config (id, interval_minutes) VALUES (1, 15)");

            // Safe migrations
            safeAddColumn(conn, "products", "category", "TEXT DEFAULT '1'");
            safeAddColumn(conn, "products", "image_path", "TEXT DEFAULT ''");
            safeAddColumn(conn, "products", "custom_emoji_id", "TEXT DEFAULT ''");
            safeAddColumn(conn, "offers", "source_bots", "TEXT DEFAULT ''");
            safeAddColumn(conn, "offers", "source_product_btn", "TEXT DEFAULT ''");
            safeAddColumn(conn, "offers", "source_offer_keyword", "TEXT DEFAULT ''");
            safeAddColumn(conn, "offers", "profit_margin", "REAL DEFAULT 0.30");
            safeAddColumn(conn, "offers", "last_source_price", "REAL DEFAULT 0.0");
            safeAddColumn(conn, "offers", "auto_sync", "INTEGER DEFAULT 0");
            safeAddColumn(conn, "stock", "offer_id", "INTEGER DEFAULT 0");
            safeAddColumn(conn, "orders", "offer_id", "INTEGER DEFAULT 0");
            safeAddColumn(conn, "orders", "payment_type", "TEXT DEFAULT 'WALLET'");
            safeAddColumn(conn, "orders", "currency", "TEXT DEFAULT 'USD'");
            safeAddColumn(conn, "orders", "amount_to_pay", "REAL DEFAULT 0.0");
            safeAddColumn(conn, "orders", "payment_method_name", "TEXT DEFAULT 'المحفظة'");
            safeAddColumn(conn, "orders", "proof_text", "TEXT DEFAULT ''");
            safeAddColumn(conn, "orders", "proof_image", "TEXT DEFAULT ''");
            safeAddColumn(conn, "orders", "rejection_reason", "TEXT DEFAULT ''");
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
        }
    }

    private void safeAddColumn(Connection conn, String table, String column, String typeDef) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + typeDef);
        } catch (SQLException ignored) {
        }
    }

    public String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ==================== USERS ====================

    public synchronized User getOrCreateUser(long chatId, String username, String firstName) {
        String selectSql = "SELECT * FROM users WHERE chat_id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                if (username != null || firstName != null) {
                    String newUsername = username != null ? username : rs.getString("username");
                    String newFirstName = firstName != null ? firstName : rs.getString("first_name");
                    String updateSql = "UPDATE users SET username = ?, first_name = ? WHERE chat_id = ?";
                    try (PreparedStatement upStmt = conn.prepareStatement(updateSql)) {
                        upStmt.setString(1, newUsername != null ? newUsername : "");
                        upStmt.setString(2, newFirstName != null ? newFirstName : "");
                        upStmt.setLong(3, chatId);
                        upStmt.executeUpdate();
                    }
                    return new User(
                            rs.getLong("chat_id"),
                            newUsername,
                            newFirstName,
                            rs.getDouble("balance"),
                            rs.getString("created_at")
                    );
                }
                return new User(
                        rs.getLong("chat_id"),
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getDouble("balance"),
                        rs.getString("created_at")
                );
            } else {
                String createdAt = now();
                String insertSql = "INSERT INTO users (chat_id, username, first_name, balance, created_at) VALUES (?, ?, ?, 0.0, ?)";
                try (PreparedStatement inStmt = conn.prepareStatement(insertSql)) {
                    inStmt.setLong(1, chatId);
                    inStmt.setString(2, username != null ? username : "");
                    inStmt.setString(3, firstName != null ? firstName : "");
                    inStmt.setString(4, createdAt);
                    inStmt.executeUpdate();
                }
                return new User(chatId, username != null ? username : "", firstName != null ? firstName : "", 0.0, createdAt);
            }
        } catch (SQLException e) {
            System.err.println("Error in getOrCreateUser: " + e.getMessage());
            return new User(chatId, username != null ? username : "", firstName != null ? firstName : "", 0.0, now());
        }
    }

    public synchronized List<User> getAllUsers() {
        List<User> list = new ArrayList<>();
        String sql = "SELECT * FROM users ORDER BY created_at DESC";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new User(
                        rs.getLong("chat_id"),
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getDouble("balance"),
                        rs.getString("created_at")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error in getAllUsers: " + e.getMessage());
        }
        return list;
    }

    public synchronized double addBalance(long chatId, double amount) {
        String checkSql = "SELECT balance FROM users WHERE chat_id = ?";
        try (Connection conn = connect();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setLong(1, chatId);
            ResultSet rs = checkStmt.executeQuery();
            if (!rs.next()) {
                String insertSql = "INSERT INTO users (chat_id, username, first_name, balance, created_at) VALUES (?, '', 'عميل', ?, ?)";
                try (PreparedStatement inStmt = conn.prepareStatement(insertSql)) {
                    inStmt.setLong(1, chatId);
                    inStmt.setDouble(2, amount);
                    inStmt.setString(3, now());
                    inStmt.executeUpdate();
                }
                return amount;
            } else {
                double newBal = rs.getDouble("balance") + amount;
                if (newBal < 0) newBal = 0;
                String updateSql = "UPDATE users SET balance = ? WHERE chat_id = ?";
                try (PreparedStatement upStmt = conn.prepareStatement(updateSql)) {
                    upStmt.setDouble(1, newBal);
                    upStmt.setLong(2, chatId);
                    upStmt.executeUpdate();
                }
                return newBal;
            }
        } catch (SQLException e) {
            System.err.println("Error in addBalance: " + e.getMessage());
            return -1;
        }
    }

    public synchronized double setBalance(long chatId, double newBalance) {
        if (newBalance < 0) newBalance = 0;
        String checkSql = "SELECT balance FROM users WHERE chat_id = ?";
        try (Connection conn = connect();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setLong(1, chatId);
            ResultSet rs = checkStmt.executeQuery();
            if (!rs.next()) {
                String insertSql = "INSERT INTO users (chat_id, username, first_name, balance, created_at) VALUES (?, '', 'عميل', ?, ?)";
                try (PreparedStatement inStmt = conn.prepareStatement(insertSql)) {
                    inStmt.setLong(1, chatId);
                    inStmt.setDouble(2, newBalance);
                    inStmt.setString(3, now());
                    inStmt.executeUpdate();
                }
                return newBalance;
            } else {
                String updateSql = "UPDATE users SET balance = ? WHERE chat_id = ?";
                try (PreparedStatement upStmt = conn.prepareStatement(updateSql)) {
                    upStmt.setDouble(1, newBalance);
                    upStmt.setLong(2, chatId);
                    upStmt.executeUpdate();
                }
                return newBalance;
            }
        } catch (SQLException e) {
            System.err.println("Error in setBalance: " + e.getMessage());
            return -1;
        }
    }

    // ==================== PAYMENT METHODS ====================

    public synchronized int addPaymentMethod(String name, String currency, String iconPath, String customEmojiId, String instructions) {
        initDatabase();
        String sql = "INSERT INTO payment_methods (name, currency, icon_path, custom_emoji_id, instructions, is_active) VALUES (?, ?, ?, ?, ?, 1)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, name);
            pstmt.setString(2, currency.toUpperCase());
            pstmt.setString(3, iconPath != null ? iconPath : "");
            pstmt.setString(4, customEmojiId != null ? customEmojiId : "");
            pstmt.setString(5, instructions != null ? instructions : "");
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("Error in addPaymentMethod: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean updatePaymentMethod(int id, String name, String currency, String instructions) {
        String sql = "UPDATE payment_methods SET name = ?, currency = ?, instructions = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, name);
            pstmt.setString(2, currency.toUpperCase());
            pstmt.setString(3, instructions);
            pstmt.setInt(4, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in updatePaymentMethod: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean deletePaymentMethod(int id) {
        String sql = "DELETE FROM payment_methods WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in deletePaymentMethod: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<PaymentMethod> getAllPaymentMethods() {
        List<PaymentMethod> list = new ArrayList<>();
        String sql = "SELECT * FROM payment_methods ORDER BY id DESC";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new PaymentMethod(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("currency"),
                        rs.getString("icon_path"),
                        rs.getString("custom_emoji_id"),
                        rs.getString("instructions"),
                        rs.getInt("is_active") == 1
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error in getAllPaymentMethods: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<PaymentMethod> getPaymentMethodsByCurrency(String currency) {
        List<PaymentMethod> list = new ArrayList<>();
        String sql = "SELECT * FROM payment_methods WHERE UPPER(currency) = UPPER(?) AND is_active = 1 ORDER BY id ASC";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, currency);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                list.add(new PaymentMethod(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("currency"),
                        rs.getString("icon_path"),
                        rs.getString("custom_emoji_id"),
                        rs.getString("instructions"),
                        rs.getInt("is_active") == 1
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error in getPaymentMethodsByCurrency: " + e.getMessage());
        }
        return list;
    }

    public synchronized PaymentMethod getPaymentMethodById(int id) {
        String sql = "SELECT * FROM payment_methods WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new PaymentMethod(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("currency"),
                        rs.getString("icon_path"),
                        rs.getString("custom_emoji_id"),
                        rs.getString("instructions"),
                        rs.getInt("is_active") == 1
                );
            }
        } catch (SQLException e) {
            System.err.println("Error in getPaymentMethodById: " + e.getMessage());
        }
        return null;
    }

    // ==================== TOPUP REQUESTS ====================

    public synchronized int createTopupRequest(long userId, String currency, double amountPaid, double amountUsd, int methodId, String methodName) {
        String sql = """
                INSERT INTO topup_requests (user_id, currency, amount_paid, amount_usd, payment_method_id, payment_method_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WAITING_PROOF', ?)
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, currency);
            pstmt.setDouble(3, amountPaid);
            pstmt.setDouble(4, amountUsd);
            pstmt.setInt(5, methodId);
            pstmt.setString(6, methodName);
            pstmt.setString(7, now());
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("Error in createTopupRequest: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean attachTopupProof(int topupId, String proofText, String proofImage) {
        String sql = "UPDATE topup_requests SET proof_text = ?, proof_image = ?, status = 'PENDING_ADMIN' WHERE id = ? AND status = 'WAITING_PROOF'";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, proofText != null ? proofText : "");
            pstmt.setString(2, proofImage != null ? proofImage : "");
            pstmt.setInt(3, topupId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in attachTopupProof: " + e.getMessage());
            return false;
        }
    }

    public synchronized TopupRequest expireTopupIfWaiting(int topupId) {
        TopupRequest req = getTopupById(topupId);
        if (req != null && "WAITING_PROOF".equals(req.status)) {
            try (Connection conn = connect();
                 PreparedStatement pstmt = conn.prepareStatement("UPDATE topup_requests SET status = 'EXPIRED' WHERE id = ?")) {
                pstmt.setInt(1, topupId);
                pstmt.executeUpdate();
                req.status = "EXPIRED";
                return req;
            } catch (SQLException e) {
                System.err.println("Error in expireTopupIfWaiting: " + e.getMessage());
            }
        }
        return null;
    }

    public synchronized TopupRequest approveTopupRequest(int topupId) {
        TopupRequest req = getTopupById(topupId);
        if (req == null || !"PENDING_ADMIN".equals(req.status)) return null;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement("UPDATE topup_requests SET status = 'APPROVED' WHERE id = ?")) {
            pstmt.setInt(1, topupId);
            if (pstmt.executeUpdate() > 0) {
                addBalance(req.userId, req.amountUsd);
                req.status = "APPROVED";
                return req;
            }
        } catch (SQLException e) {
            System.err.println("Error in approveTopupRequest: " + e.getMessage());
        }
        return null;
    }

    public synchronized TopupRequest rejectTopupRequest(int topupId, String reason) {
        TopupRequest req = getTopupById(topupId);
        if (req == null) return null;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement("UPDATE topup_requests SET status = 'REJECTED', rejection_reason = ? WHERE id = ?")) {
            pstmt.setString(1, reason != null ? reason : "");
            pstmt.setInt(2, topupId);
            if (pstmt.executeUpdate() > 0) {
                req.status = "REJECTED";
                req.rejectionReason = reason;
                return req;
            }
        } catch (SQLException e) {
            System.err.println("Error in rejectTopupRequest: " + e.getMessage());
        }
        return null;
    }

    public synchronized TopupRequest getTopupById(int topupId) {
        String sql = """
                SELECT t.*, u.first_name AS user_name
                FROM topup_requests t
                LEFT JOIN users u ON u.chat_id = t.user_id
                WHERE t.id = ?
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, topupId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new TopupRequest(
                        rs.getInt("id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name") != null ? rs.getString("user_name") : "عميل",
                        rs.getString("currency"),
                        rs.getDouble("amount_paid"),
                        rs.getDouble("amount_usd"),
                        rs.getInt("payment_method_id"),
                        rs.getString("payment_method_name"),
                        rs.getString("proof_text"),
                        rs.getString("proof_image"),
                        rs.getString("status"),
                        rs.getString("rejection_reason"),
                        rs.getString("created_at")
                );
            }
        } catch (SQLException e) {
            System.err.println("Error in getTopupById: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<TopupRequest> getAllTopupRequests() {
        List<TopupRequest> list = new ArrayList<>();
        String sql = """
                SELECT t.*, u.first_name AS user_name
                FROM topup_requests t
                LEFT JOIN users u ON u.chat_id = t.user_id
                WHERE t.status != 'WAITING_PROOF' AND t.status != 'EXPIRED'
                ORDER BY CASE WHEN t.status = 'PENDING_ADMIN' THEN 0 ELSE 1 END, t.id DESC
                """;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new TopupRequest(
                        rs.getInt("id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name") != null ? rs.getString("user_name") : "عميل",
                        rs.getString("currency"),
                        rs.getDouble("amount_paid"),
                        rs.getDouble("amount_usd"),
                        rs.getInt("payment_method_id"),
                        rs.getString("payment_method_name"),
                        rs.getString("proof_text"),
                        rs.getString("proof_image"),
                        rs.getString("status"),
                        rs.getString("rejection_reason"),
                        rs.getString("created_at")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error in getAllTopupRequests: " + e.getMessage());
        }
        return list;
    }

    // ==================== PRODUCTS & OFFERS ====================

    public synchronized int addProductWithDetails(String category, String name, String imagePath, String customEmojiId) {
        String sql = "INSERT INTO products (category, name, price, image_path, custom_emoji_id) VALUES (?, ?, 0.0, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, category != null ? category : "1");
            pstmt.setString(2, name);
            pstmt.setString(3, imagePath != null ? imagePath : "");
            pstmt.setString(4, customEmojiId != null ? customEmojiId : "");
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error in addProductWithDetails: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean updateProductLogo(int productId, String imagePath, String customEmojiId) {
        String sql = "UPDATE products SET image_path = ?, custom_emoji_id = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, imagePath != null ? imagePath : "");
            pstmt.setString(2, customEmojiId != null ? customEmojiId : "");
            pstmt.setInt(3, productId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in updateProductLogo: " + e.getMessage());
            return false;
        }
    }

    public synchronized int addProduct(String name, double price) {
        int prodId = addProductWithDetails("1", name, "", "");
        if (prodId != -1) {
            addOffer(prodId, name + " - باقة أساسية", price, "تسليم فوري وآلي", true, null);
        }
        return prodId;
    }

    public synchronized boolean deleteProduct(int productId) {
        try (Connection conn = connect()) {
            try (PreparedStatement s1 = conn.prepareStatement("DELETE FROM offers WHERE product_id = ?");
                 PreparedStatement s2 = conn.prepareStatement("DELETE FROM stock WHERE product_id = ? AND is_sold = 0");
                 PreparedStatement s3 = conn.prepareStatement("DELETE FROM products WHERE id = ?")) {
                s1.setInt(1, productId);
                s1.executeUpdate();
                s2.setInt(1, productId);
                s2.executeUpdate();
                s3.setInt(1, productId);
                return s3.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("Error in deleteProduct: " + e.getMessage());
            return false;
        }
    }

    public synchronized int addOffer(int productId, String name, double price, String description, boolean isAvailable, String initialStockLines) {
        String sql = "INSERT INTO offers (product_id, name, price, description, is_available) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, productId);
            pstmt.setString(2, name);
            pstmt.setDouble(3, price);
            pstmt.setString(4, description != null ? description : "");
            pstmt.setInt(5, isAvailable ? 1 : 0);
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                int offerId = rs.getInt(1);
                if (initialStockLines != null && !initialStockLines.isBlank()) {
                    String[] lines = initialStockLines.split("\\r?\\n");
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            addOfferStockInternal(conn, productId, offerId, line.trim());
                        }
                    }
                }
                return offerId;
            }
        } catch (SQLException e) {
            System.err.println("Error in addOffer: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean deleteOffer(int offerId) {
        try (Connection conn = connect();
             PreparedStatement s1 = conn.prepareStatement("DELETE FROM stock WHERE offer_id = ? AND is_sold = 0");
             PreparedStatement s2 = conn.prepareStatement("DELETE FROM offers WHERE id = ?")) {
            s1.setInt(1, offerId);
            s1.executeUpdate();
            s2.setInt(1, offerId);
            return s2.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in deleteOffer: " + e.getMessage());
            return false;
        }
    }

    private void addOfferStockInternal(Connection conn, int productId, int offerId, String accountData) throws SQLException {
        String sql = "INSERT INTO stock (product_id, offer_id, account_data, is_sold) VALUES (?, ?, ?, 0)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, productId);
            pstmt.setInt(2, offerId);
            pstmt.setString(3, accountData);
            pstmt.executeUpdate();
        }
    }

    public synchronized int addOfferStockBatch(int offerId, String stockLines) {
        Offer offer = getOfferById(offerId);
        if (offer == null || stockLines == null || stockLines.isBlank()) return 0;
        int count = 0;
        try (Connection conn = connect()) {
            String[] lines = stockLines.split("\\r?\\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    addOfferStockInternal(conn, offer.productId, offerId, line.trim());
                    count++;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error in addOfferStockBatch: " + e.getMessage());
        }
        return count;
    }

    public synchronized boolean addStock(int productId, String accountData) {
        Product product = getProductById(productId);
        if (product == null) return false;
        int offerId;
        if (!product.offers.isEmpty()) {
            offerId = product.offers.get(0).id;
        } else {
            offerId = addOffer(productId, product.name, product.price, "تسليم فوري", true, null);
        }
        try (Connection conn = connect()) {
            addOfferStockInternal(conn, productId, offerId, accountData);
            return true;
        } catch (SQLException e) {
            System.err.println("Error in addStock: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean updateOfferAvailability(int offerId, boolean isAvailable) {
        String sql = "UPDATE offers SET is_available = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, isAvailable ? 1 : 0);
            pstmt.setInt(2, offerId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in updateOfferAvailability: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean updateOfferDetails(int offerId, String name, double price, String description) {
        String sql = "UPDATE offers SET name = ?, price = ?, description = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, name);
            pstmt.setDouble(2, price);
            pstmt.setString(3, description != null ? description : "");
            pstmt.setInt(4, offerId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in updateOfferDetails: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean updateProductDetails(int productId, String category, String name) {
        String sql = "UPDATE products SET category = ?, name = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, category);
            pstmt.setString(2, name);
            pstmt.setInt(3, productId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in updateProductDetails: " + e.getMessage());
            return false;
        }
    }

    public synchronized Product getProductById(int productId) {
        String sql = "SELECT * FROM products WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, productId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Product p = new Product(
                        rs.getInt("id"),
                        rs.getString("category"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getString("image_path"),
                        rs.getString("custom_emoji_id")
                );
                p.offers = getOffersByProductId(p.id);
                return p;
            }
        } catch (SQLException e) {
            System.err.println("Error in getProductById: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<Product> getProductsByCategory(String category) {
        List<Product> products = new ArrayList<>();
        String sql = "SELECT * FROM products WHERE category = ? ORDER BY id ASC";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, category);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Product p = new Product(
                        rs.getInt("id"),
                        rs.getString("category"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getString("image_path"),
                        rs.getString("custom_emoji_id")
                );
                p.offers = getOffersByProductId(p.id);
                products.add(p);
            }
        } catch (SQLException e) {
            System.err.println("Error in getProductsByCategory: " + e.getMessage());
        }
        return products;
    }

    public synchronized List<Product> getAllProducts() {
        List<Product> products = new ArrayList<>();
        String sql = "SELECT * FROM products ORDER BY id DESC";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Product p = new Product(
                        rs.getInt("id"),
                        rs.getString("category"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getString("image_path"),
                        rs.getString("custom_emoji_id")
                );
                p.offers = getOffersByProductId(p.id);
                products.add(p);
            }
        } catch (SQLException e) {
            System.err.println("Error in getAllProducts: " + e.getMessage());
        }
        return products;
    }

    public synchronized List<Offer> getOffersByProductId(int productId) {
        List<Offer> offers = new ArrayList<>();
        String sql = """
                SELECT o.*, p.name AS product_name,
                       (SELECT COUNT(*) FROM stock s WHERE (s.offer_id = o.id OR (s.offer_id = 0 AND s.product_id = o.product_id)) AND s.is_sold = 0) AS available_stock
                FROM offers o
                JOIN products p ON p.id = o.product_id
                WHERE o.product_id = ?
                ORDER BY o.id ASC
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, productId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                offers.add(mapOfferRow(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error in getOffersByProductId: " + e.getMessage());
        }
        return offers;
    }

    public synchronized Offer getOfferById(int offerId) {
        String sql = """
                SELECT o.*, p.name AS product_name,
                       (SELECT COUNT(*) FROM stock s WHERE (s.offer_id = o.id OR (s.offer_id = 0 AND s.product_id = o.product_id)) AND s.is_sold = 0) AS available_stock
                FROM offers o
                JOIN products p ON p.id = o.product_id
                WHERE o.id = ?
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, offerId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return mapOfferRow(rs);
            }
        } catch (SQLException e) {
            System.err.println("Error in getOfferById: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<Offer> getAutoSyncOffers() {
        List<Offer> offers = new ArrayList<>();
        String sql = """
                SELECT o.*, p.name AS product_name,
                       (SELECT COUNT(*) FROM stock s WHERE (s.offer_id = o.id OR (s.offer_id = 0 AND s.product_id = o.product_id)) AND s.is_sold = 0) AS available_stock
                FROM offers o
                JOIN products p ON p.id = o.product_id
                WHERE o.auto_sync = 1 AND o.source_bots != ''
                ORDER BY o.id ASC
                """;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                offers.add(mapOfferRow(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error in getAutoSyncOffers: " + e.getMessage());
        }
        return offers;
    }

    private Offer mapOfferRow(ResultSet rs) throws SQLException {
        double margin = 0.30;
        try {
            margin = rs.getDouble("profit_margin");
        } catch (SQLException ignored) {
        }
        return new Offer(
                rs.getInt("id"),
                rs.getInt("product_id"),
                rs.getString("product_name"),
                rs.getString("name"),
                rs.getDouble("price"),
                rs.getString("description"),
                rs.getInt("is_available") == 1,
                rs.getInt("available_stock"),
                rs.getString("source_bots"),
                rs.getString("source_product_btn"),
                rs.getString("source_offer_keyword"),
                margin,
                rs.getDouble("last_source_price"),
                rs.getInt("auto_sync") == 1
        );
    }

    public synchronized boolean updateOfferWatcherSettings(int offerId, String sourceBots, String sourceProductBtn,
                                                           String sourceOfferKeyword, double profitMargin, boolean autoSync) {
        initDatabase();
        String sql = """
                UPDATE offers
                SET source_bots = ?, source_product_btn = ?, source_offer_keyword = ?, profit_margin = ?, auto_sync = ?
                WHERE id = ?
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sourceBots != null ? sourceBots.trim() : "");
            pstmt.setString(2, sourceProductBtn != null ? sourceProductBtn.trim() : "");
            pstmt.setString(3, sourceOfferKeyword != null ? sourceOfferKeyword.trim() : "");
            pstmt.setDouble(4, profitMargin);
            pstmt.setInt(5, autoSync ? 1 : 0);
            pstmt.setInt(6, offerId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in updateOfferWatcherSettings: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean applySyncedOfferResult(int offerId, double sourcePrice, double finalSellPrice, boolean isAvailable) {
        String sql = "UPDATE offers SET last_source_price = ?, price = ?, is_available = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, sourcePrice);
            pstmt.setDouble(2, finalSellPrice);
            pstmt.setInt(3, isAvailable ? 1 : 0);
            pstmt.setInt(4, offerId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in applySyncedOfferResult: " + e.getMessage());
            return false;
        }
    }

    public synchronized void updateProductCustomEmojiIfEmpty(int productId, String customEmojiId) {
        if (customEmojiId == null || customEmojiId.isBlank()) return;
        String sql = "UPDATE products SET custom_emoji_id = ? WHERE id = ? AND (custom_emoji_id IS NULL OR custom_emoji_id = '')";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, customEmojiId.trim());
            pstmt.setInt(2, productId);
            pstmt.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    // ==================== WATCHER CONFIG ====================

    public synchronized WatcherConfig getWatcherConfig() {
        initDatabase();
        String sql = "SELECT * FROM watcher_config WHERE id = 1";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new WatcherConfig(
                        rs.getString("api_id"),
                        rs.getString("api_hash"),
                        rs.getString("phone"),
                        rs.getString("session_string"),
                        rs.getInt("is_connected") == 1,
                        rs.getInt("interval_minutes"),
                        rs.getString("last_sync_at"),
                        rs.getString("last_sync_log")
                );
            }
        } catch (SQLException e) {
            System.err.println("Error in getWatcherConfig: " + e.getMessage());
        }
        return new WatcherConfig("", "", "", "", false, 15, "", "");
    }

    public synchronized void saveWatcherCredentials(String apiId, String apiHash, String phone, int intervalMinutes) {
        initDatabase();
        String sql = "UPDATE watcher_config SET api_id = ?, api_hash = ?, phone = ?, interval_minutes = ? WHERE id = 1";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, apiId != null ? apiId.trim() : "");
            pstmt.setString(2, apiHash != null ? apiHash.trim() : "");
            pstmt.setString(3, phone != null ? phone.trim() : "");
            pstmt.setInt(4, intervalMinutes > 0 ? intervalMinutes : 15);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error in saveWatcherCredentials: " + e.getMessage());
        }
    }

    public synchronized void saveWatcherSession(String sessionString, boolean isConnected) {
        initDatabase();
        String sql = "UPDATE watcher_config SET session_string = ?, is_connected = ? WHERE id = 1";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionString != null ? sessionString.trim() : "");
            pstmt.setInt(2, isConnected ? 1 : 0);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error in saveWatcherSession: " + e.getMessage());
        }
    }

    public synchronized void saveWatcherSyncLog(String logText) {
        initDatabase();
        String sql = "UPDATE watcher_config SET last_sync_at = ?, last_sync_log = ? WHERE id = 1";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, now());
            pstmt.setString(2, logText != null ? logText : "");
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error in saveWatcherSyncLog: " + e.getMessage());
        }
    }

    // ==================== AVAILABILITY NOTIFICATIONS ====================

    public synchronized boolean addAvailabilityNotification(long userId, int offerId) {
        String sql = "INSERT OR IGNORE INTO availability_notifications (user_id, offer_id, created_at) VALUES (?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, offerId);
            pstmt.setString(3, now());
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("Error in addAvailabilityNotification: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<Long> popWaitingUsersForOffer(int offerId) {
        List<Long> userIds = new ArrayList<>();
        String selectSql = "SELECT user_id FROM availability_notifications WHERE offer_id = ?";
        String deleteSql = "DELETE FROM availability_notifications WHERE offer_id = ?";
        try (Connection conn = connect()) {
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setInt(1, offerId);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    userIds.add(rs.getLong("user_id"));
                }
            }
            if (!userIds.isEmpty()) {
                try (PreparedStatement delStmt = conn.prepareStatement(deleteSql)) {
                    delStmt.setInt(1, offerId);
                    delStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("Error in popWaitingUsersForOffer: " + e.getMessage());
        }
        return userIds;
    }

    // ==================== PURCHASE & ORDERS ====================

    /**
     * Wallet purchase:
     * - Checks balance >= price.
     * - If stock is available: deducts balance immediately, marks stock sold, status = COMPLETED.
     * - If stock is NOT available: keeps status = PENDING (paymentType=WALLET), and balance is deducted when Admin approves & delivers!
     *   (Or we check balance now, and deduct upon Admin delivery as requested: "و إذا كان من المحفظة فإذا وافقت و أرسلت المنتوج يتم خصم المبلغ من محفضته و إذا رفضت فسأكتب السبب في الداشبورد ويتم إرساله له").
     */
    public synchronized PurchaseResult purchaseOfferWithWallet(long userId, int offerId) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                String offerSql = """
                        SELECT o.id, o.product_id, o.name, o.price, o.is_available, p.name AS product_name
                        FROM offers o
                        JOIN products p ON p.id = o.product_id
                        WHERE o.id = ?
                        """;
                int productId;
                String fullOfferName;
                double price;
                boolean isAvailable;
                try (PreparedStatement oStmt = conn.prepareStatement(offerSql)) {
                    oStmt.setInt(1, offerId);
                    ResultSet oRs = oStmt.executeQuery();
                    if (!oRs.next()) {
                        conn.rollback();
                        return new PurchaseResult(PurchaseResult.Status.OFFER_NOT_FOUND, null, 0, 0);
                    }
                    productId = oRs.getInt("product_id");
                    fullOfferName = oRs.getString("product_name") + " - " + oRs.getString("name");
                    price = oRs.getDouble("price");
                    isAvailable = oRs.getInt("is_available") == 1;
                }

                if (!isAvailable) {
                    conn.rollback();
                    return new PurchaseResult(PurchaseResult.Status.OFFER_UNAVAILABLE, null, 0, price);
                }

                String userSql = "SELECT balance, first_name FROM users WHERE chat_id = ?";
                double balance = 0.0;
                String userName = "";
                try (PreparedStatement uStmt = conn.prepareStatement(userSql)) {
                    uStmt.setLong(1, userId);
                    ResultSet uRs = uStmt.executeQuery();
                    if (uRs.next()) {
                        balance = uRs.getDouble("balance");
                        userName = uRs.getString("first_name");
                    }
                }

                if (balance < price) {
                    conn.rollback();
                    return new PurchaseResult(PurchaseResult.Status.INSUFFICIENT_BALANCE, null, balance, price);
                }

                // Check automatic stock
                String stockSql = """
                        SELECT id, account_data FROM stock
                        WHERE (offer_id = ? OR (offer_id = 0 AND product_id = ?)) AND is_sold = 0
                        ORDER BY id ASC LIMIT 1
                        """;
                Integer stockId = null;
                String accountData = null;
                try (PreparedStatement sStmt = conn.prepareStatement(stockSql)) {
                    sStmt.setInt(1, offerId);
                    sStmt.setInt(2, productId);
                    ResultSet sRs = sStmt.executeQuery();
                    if (sRs.next()) {
                        stockId = sRs.getInt("id");
                        accountData = sRs.getString("account_data");
                    }
                }

                String status;
                double newBalance = balance;
                if (stockId != null) {
                    status = "COMPLETED";
                    newBalance = balance - price;
                    try (PreparedStatement dStmt = conn.prepareStatement("UPDATE users SET balance = ? WHERE chat_id = ?")) {
                        dStmt.setDouble(1, newBalance);
                        dStmt.setLong(2, userId);
                        dStmt.executeUpdate();
                    }
                    try (PreparedStatement mStmt = conn.prepareStatement("UPDATE stock SET is_sold = 1 WHERE id = ?")) {
                        mStmt.setInt(1, stockId);
                        mStmt.executeUpdate();
                    }
                } else {
                    // Pending admin approval & delivery: balance will be deducted when admin approves!
                    status = "PENDING";
                }

                String createdAt = now();
                String orderSql = """
                        INSERT INTO orders (user_id, product_id, offer_id, product_name, price, payment_type, currency, amount_to_pay, payment_method_name, account_data, status, created_at)
                        VALUES (?, ?, ?, ?, ?, 'WALLET', 'USD', ?, 'رصيد المحفظة', ?, ?, ?)
                        """;
                int orderId = -1;
                try (PreparedStatement ordStmt = conn.prepareStatement(orderSql, Statement.RETURN_GENERATED_KEYS)) {
                    ordStmt.setLong(1, userId);
                    ordStmt.setInt(2, productId);
                    ordStmt.setInt(3, offerId);
                    ordStmt.setString(4, fullOfferName);
                    ordStmt.setDouble(5, price);
                    ordStmt.setDouble(6, price);
                    ordStmt.setString(7, accountData);
                    ordStmt.setString(8, status);
                    ordStmt.setString(9, createdAt);
                    ordStmt.executeUpdate();
                    ResultSet ordRs = ordStmt.getGeneratedKeys();
                    if (ordRs.next()) {
                        orderId = ordRs.getInt(1);
                    }
                }

                conn.commit();
                Order order = new Order(orderId, userId, userName, productId, offerId, fullOfferName, price,
                        "WALLET", "USD", price, "رصيد المحفظة", "", "", accountData, status, "", createdAt);
                PurchaseResult.Status resStatus = "COMPLETED".equals(status)
                        ? PurchaseResult.Status.DELIVERED
                        : PurchaseResult.Status.PENDING;
                return new PurchaseResult(resStatus, order, newBalance, price);
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("Error in purchaseOfferWithWallet: " + e.getMessage());
            return new PurchaseResult(PurchaseResult.Status.ERROR, null, 0, 0);
        }
    }

    /**
     * Direct payment order creation (starts in WAITING_PROOF for 20 minutes).
     */
    public synchronized int createDirectPaymentOrder(long userId, int offerId, String currency, double amountToPay, String paymentMethodName) {
        Offer offer = getOfferById(offerId);
        if (offer == null) return -1;
        String fullOfferName = offer.productName + " - " + offer.name;
        String sql = """
                INSERT INTO orders (user_id, product_id, offer_id, product_name, price, payment_type, currency, amount_to_pay, payment_method_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'DIRECT', ?, ?, ?, 'WAITING_PROOF', ?)
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, offer.productId);
            pstmt.setInt(3, offerId);
            pstmt.setString(4, fullOfferName);
            pstmt.setDouble(5, offer.price);
            pstmt.setString(6, currency);
            pstmt.setDouble(7, amountToPay);
            pstmt.setString(8, paymentMethodName);
            pstmt.setString(9, now());
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("Error in createDirectPaymentOrder: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean attachOrderPaymentProof(int orderId, String proofText, String proofImage) {
        String sql = "UPDATE orders SET proof_text = ?, proof_image = ?, status = 'PENDING' WHERE id = ? AND status = 'WAITING_PROOF'";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, proofText != null ? proofText : "");
            pstmt.setString(2, proofImage != null ? proofImage : "");
            pstmt.setInt(3, orderId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error in attachOrderPaymentProof: " + e.getMessage());
            return false;
        }
    }

    public synchronized Order expireOrderIfWaiting(int orderId) {
        Order ord = getOrderById(orderId);
        if (ord != null && "WAITING_PROOF".equals(ord.status)) {
            try (Connection conn = connect();
                 PreparedStatement pstmt = conn.prepareStatement("UPDATE orders SET status = 'EXPIRED' WHERE id = ?")) {
                pstmt.setInt(1, orderId);
                pstmt.executeUpdate();
                ord.status = "EXPIRED";
                return ord;
            } catch (SQLException e) {
                System.err.println("Error in expireOrderIfWaiting: " + e.getMessage());
            }
        }
        return null;
    }

    public synchronized Order getOrderById(int orderId) {
        String selectSql = """
                SELECT o.*, u.first_name AS user_name
                FROM orders o
                LEFT JOIN users u ON u.chat_id = o.user_id
                WHERE o.id = ?
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setInt(1, orderId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return mapOrderRow(rs);
            }
        } catch (SQLException e) {
            System.err.println("Error in getOrderById: " + e.getMessage());
        }
        return null;
    }

    /**
     * Completes a PENDING order when Admin delivers the account from the dashboard.
     * If paymentType == "WALLET", deducts the price from the user's wallet now!
     */
    public synchronized Order completePendingOrder(int orderId, String accountData) {
        Order ord = getOrderById(orderId);
        if (ord == null) return null;

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                if ("WALLET".equalsIgnoreCase(ord.paymentType) && "PENDING".equalsIgnoreCase(ord.status)) {
                    double currentBal = 0.0;
                    try (PreparedStatement uStmt = conn.prepareStatement("SELECT balance FROM users WHERE chat_id = ?")) {
                        uStmt.setLong(1, ord.userId);
                        ResultSet uRs = uStmt.executeQuery();
                        if (uRs.next()) currentBal = uRs.getDouble("balance");
                    }
                    double newBal = Math.max(0.0, currentBal - ord.price);
                    try (PreparedStatement dStmt = conn.prepareStatement("UPDATE users SET balance = ? WHERE chat_id = ?")) {
                        dStmt.setDouble(1, newBal);
                        dStmt.setLong(2, ord.userId);
                        dStmt.executeUpdate();
                    }
                }

                String updateSql = "UPDATE orders SET account_data = ?, status = 'COMPLETED' WHERE id = ?";
                try (PreparedStatement upStmt = conn.prepareStatement(updateSql)) {
                    upStmt.setString(1, accountData);
                    upStmt.setInt(2, orderId);
                    upStmt.executeUpdate();
                }

                conn.commit();
                ord.accountData = accountData;
                ord.status = "COMPLETED";
                return ord;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("Error in completePendingOrder: " + e.getMessage());
        }
        return null;
    }

    public synchronized Order rejectPendingOrder(int orderId, String reason) {
        Order ord = getOrderById(orderId);
        if (ord == null) return null;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement("UPDATE orders SET status = 'REJECTED', rejection_reason = ? WHERE id = ?")) {
            pstmt.setString(1, reason != null ? reason : "");
            pstmt.setInt(2, orderId);
            if (pstmt.executeUpdate() > 0) {
                ord.status = "REJECTED";
                ord.rejectionReason = reason;
                return ord;
            }
        } catch (SQLException e) {
            System.err.println("Error in rejectPendingOrder: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<Order> getAllOrders() {
        List<Order> orders = new ArrayList<>();
        String sql = """
                SELECT o.*, u.first_name AS user_name
                FROM orders o
                LEFT JOIN users u ON u.chat_id = o.user_id
                WHERE o.status != 'WAITING_PROOF' AND o.status != 'EXPIRED'
                ORDER BY CASE WHEN o.status = 'PENDING' THEN 0 ELSE 1 END, o.id DESC
                """;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                orders.add(mapOrderRow(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error in getAllOrders: " + e.getMessage());
        }
        return orders;
    }

    public synchronized List<Order> getUserOrders(long userId, int limit) {
        List<Order> orders = new ArrayList<>();
        String sql = """
                SELECT o.*, '' AS user_name
                FROM orders o
                WHERE o.user_id = ? AND o.status != 'WAITING_PROOF'
                ORDER BY o.id DESC LIMIT ?
                """;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, limit);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                orders.add(mapOrderRow(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error in getUserOrders: " + e.getMessage());
        }
        return orders;
    }

    private Order mapOrderRow(ResultSet rs) throws SQLException {
        return new Order(
                rs.getInt("id"),
                rs.getLong("user_id"),
                rs.getString("user_name") != null ? rs.getString("user_name") : "عميل",
                rs.getInt("product_id"),
                rs.getInt("offer_id"),
                rs.getString("product_name"),
                rs.getDouble("price"),
                rs.getString("payment_type"),
                rs.getString("currency"),
                rs.getDouble("amount_to_pay"),
                rs.getString("payment_method_name"),
                rs.getString("proof_text"),
                rs.getString("proof_image"),
                rs.getString("account_data"),
                rs.getString("status"),
                rs.getString("rejection_reason"),
                rs.getString("created_at")
        );
    }

    public synchronized int getUserOrdersCount(long userId) {
        String sql = "SELECT COUNT(*) FROM orders WHERE user_id = ? AND status != 'WAITING_PROOF'";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error in getUserOrdersCount: " + e.getMessage());
        }
        return 0;
    }
}
