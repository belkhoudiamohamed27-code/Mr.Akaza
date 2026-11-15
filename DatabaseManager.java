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

        public Offer(int id, int productId, String productName, String name, double price, String description, boolean isAvailable, int availableStock) {
            this.id = id;
            this.productId = productId;
            this.productName = productName;
            this.name = name;
            this.price = price;
            this.description = description;
            this.isAvailable = isAvailable;
            this.availableStock = availableStock;
        }
    }

    public static class Order {
        public int id;
        public long userId;
        public String userName;
        public int productId;
        public int offerId;
        public String productName;
        public double price;
        public String accountData;
        public String status;
        public String createdAt;

        public Order(int id, long userId, String userName, int productId, int offerId, String productName, double price, String accountData, String status, String createdAt) {
            this.id = id;
            this.userId = userId;
            this.userName = userName;
            this.productId = productId;
            this.offerId = offerId;
            this.productName = productName;
            this.price = price;
            this.accountData = accountData;
            this.status = status;
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
                    image_path TEXT DEFAULT ''
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
                    account_data TEXT,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(chat_id),
                    FOREIGN KEY (product_id) REFERENCES products(id)
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

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createUsers);
            stmt.execute(createProducts);
            stmt.execute(createOffers);
            stmt.execute(createStock);
            stmt.execute(createOrders);
            stmt.execute(createNotifications);

            // Safe migrations for existing v1 tables
            safeAddColumn(conn, "products", "category", "TEXT DEFAULT '1'");
            safeAddColumn(conn, "products", "image_path", "TEXT DEFAULT ''");
            safeAddColumn(conn, "products", "custom_emoji_id", "TEXT DEFAULT ''");
            safeAddColumn(conn, "stock", "offer_id", "INTEGER DEFAULT 0");
            safeAddColumn(conn, "orders", "offer_id", "INTEGER DEFAULT 0");
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
        }
    }

    private void safeAddColumn(Connection conn, String table, String column, String typeDef) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + typeDef);
        } catch (SQLException ignored) {
            // Column already exists
        }
    }

    private String now() {
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

    // Backwards compatible for /addprod command
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

    // Backwards compatible for /addstock command
    public synchronized boolean addStock(int productId, String accountData) {
        Product product = getProductById(productId);
        if (product == null) return false;
        int offerId = 0;
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
                offers.add(new Offer(
                        rs.getInt("id"),
                        rs.getInt("product_id"),
                        rs.getString("product_name"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getString("description"),
                        rs.getInt("is_available") == 1,
                        rs.getInt("available_stock")
                ));
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
                return new Offer(
                        rs.getInt("id"),
                        rs.getInt("product_id"),
                        rs.getString("product_name"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getString("description"),
                        rs.getInt("is_available") == 1,
                        rs.getInt("available_stock")
                );
            }
        } catch (SQLException e) {
            System.err.println("Error in getOfferById: " + e.getMessage());
        }
        return null;
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

    public synchronized PurchaseResult purchaseOffer(long userId, int offerId) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                // 1. Check offer
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

                // 2. Check user balance
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

                // 3. Deduct price
                double newBalance = balance - price;
                String deductSql = "UPDATE users SET balance = ? WHERE chat_id = ?";
                try (PreparedStatement dStmt = conn.prepareStatement(deductSql)) {
                    dStmt.setDouble(1, newBalance);
                    dStmt.setLong(2, userId);
                    dStmt.executeUpdate();
                }

                // 4. Search stock for available account for this offer
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
                if (stockId != null) {
                    status = "COMPLETED";
                    String markSoldSql = "UPDATE stock SET is_sold = 1 WHERE id = ?";
                    try (PreparedStatement mStmt = conn.prepareStatement(markSoldSql)) {
                        mStmt.setInt(1, stockId);
                        mStmt.executeUpdate();
                    }
                } else {
                    status = "PENDING";
                }

                // 5. Create order record
                String createdAt = now();
                String orderSql = """
                        INSERT INTO orders (user_id, product_id, offer_id, product_name, price, account_data, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                int orderId = -1;
                try (PreparedStatement ordStmt = conn.prepareStatement(orderSql, Statement.RETURN_GENERATED_KEYS)) {
                    ordStmt.setLong(1, userId);
                    ordStmt.setInt(2, productId);
                    ordStmt.setInt(3, offerId);
                    ordStmt.setString(4, fullOfferName);
                    ordStmt.setDouble(5, price);
                    ordStmt.setString(6, accountData);
                    ordStmt.setString(7, status);
                    ordStmt.setString(8, createdAt);
                    ordStmt.executeUpdate();
                    ResultSet ordRs = ordStmt.getGeneratedKeys();
                    if (ordRs.next()) {
                        orderId = ordRs.getInt(1);
                    }
                }

                conn.commit();
                Order order = new Order(orderId, userId, userName, productId, offerId, fullOfferName, price, accountData, status, createdAt);
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
            System.err.println("Error in purchaseOffer: " + e.getMessage());
            return new PurchaseResult(PurchaseResult.Status.ERROR, null, 0, 0);
        }
    }

    public synchronized Order completePendingOrder(int orderId, String accountData) {
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
                String updateSql = "UPDATE orders SET account_data = ?, status = 'COMPLETED' WHERE id = ?";
                try (PreparedStatement upStmt = conn.prepareStatement(updateSql)) {
                    upStmt.setString(1, accountData);
                    upStmt.setInt(2, orderId);
                    upStmt.executeUpdate();
                }
                return new Order(
                        rs.getInt("id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name"),
                        rs.getInt("product_id"),
                        rs.getInt("offer_id"),
                        rs.getString("product_name"),
                        rs.getDouble("price"),
                        accountData,
                        "COMPLETED",
                        rs.getString("created_at")
                );
            }
        } catch (SQLException e) {
            System.err.println("Error in completePendingOrder: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<Order> getAllOrders() {
        List<Order> orders = new ArrayList<>();
        String sql = """
                SELECT o.*, u.first_name AS user_name
                FROM orders o
                LEFT JOIN users u ON u.chat_id = o.user_id
                ORDER BY CASE WHEN o.status = 'PENDING' THEN 0 ELSE 1 END, o.id DESC
                """;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                orders.add(new Order(
                        rs.getInt("id"),
                        rs.getLong("user_id"),
                        rs.getString("user_name") != null ? rs.getString("user_name") : "عميل",
                        rs.getInt("product_id"),
                        rs.getInt("offer_id"),
                        rs.getString("product_name"),
                        rs.getDouble("price"),
                        rs.getString("account_data"),
                        rs.getString("status"),
                        rs.getString("created_at")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error in getAllOrders: " + e.getMessage());
        }
        return orders;
    }

    public synchronized List<Order> getUserOrders(long userId, int limit) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE user_id = ? ORDER BY id DESC LIMIT ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, limit);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                orders.add(new Order(
                        rs.getInt("id"),
                        rs.getLong("user_id"),
                        "",
                        rs.getInt("product_id"),
                        rs.getInt("offer_id"),
                        rs.getString("product_name"),
                        rs.getDouble("price"),
                        rs.getString("account_data"),
                        rs.getString("status"),
                        rs.getString("created_at")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error in getUserOrders: " + e.getMessage());
        }
        return orders;
    }

    public synchronized int getUserOrdersCount(long userId) {
        String sql = "SELECT COUNT(*) FROM orders WHERE user_id = ?";
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
