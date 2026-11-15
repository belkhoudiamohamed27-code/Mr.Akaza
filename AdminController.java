package com.mohservice;

import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AdminController {

    // Admin Password for Web Panel
    public static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "moh@#$$0808");

    private final DatabaseManager db;
    private final MohServiceBot bot;
    private final TelegramEmojiService emojiService;

    public AdminController(DatabaseManager db, MohServiceBot bot, TelegramEmojiService emojiService) {
        this.db = db;
        this.bot = bot;
        this.emojiService = emojiService;
        new File("uploads").mkdirs();
    }

    private boolean isAuthenticated(HttpSession session) {
        Boolean auth = (Boolean) session.getAttribute("ADMIN_AUTH");
        return auth != null && auth;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpSession session) {
        String password = body.get("password");
        if (ADMIN_PASSWORD.equals(password)) {
            session.setAttribute("ADMIN_AUTH", true);
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "كلمة المرور غير صحيحة"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/auth-check")
    public ResponseEntity<?> checkAuth(HttpSession session) {
        return ResponseEntity.ok(Map.of("authenticated", isAuthenticated(session)));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboardData(HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        List<DatabaseManager.User> users = db.getAllUsers();
        List<DatabaseManager.Order> orders = db.getAllOrders();
        List<DatabaseManager.Product> products = db.getAllProducts();
        List<DatabaseManager.PaymentMethod> paymentMethods = db.getAllPaymentMethods();
        List<DatabaseManager.TopupRequest> topups = db.getAllTopupRequests();

        long pendingOrdersCount = orders.stream().filter(o -> "PENDING".equals(o.status)).count();
        long pendingTopupsCount = topups.stream().filter(t -> "PENDING_ADMIN".equals(t.status)).count();
        double totalSales = orders.stream().filter(o -> "COMPLETED".equals(o.status)).mapToDouble(o -> o.price).sum();

        Map<String, Object> data = new HashMap<>();
        data.put("users", users);
        data.put("orders", orders);
        data.put("products", products);
        data.put("paymentMethods", paymentMethods);
        data.put("topups", topups);
        data.put("stats", Map.of(
                "usersCount", users.size(),
                "ordersCount", orders.size(),
                "pendingCount", pendingOrdersCount,
                "pendingTopupsCount", pendingTopupsCount,
                "totalSales", totalSales
        ));
        return ResponseEntity.ok(data);
    }

    // ==================== 1. USERS (ACCOUNTS) ====================

    @PostMapping("/users/balance")
    public ResponseEntity<?> updateUserBalance(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            long chatId = Long.parseLong(String.valueOf(body.get("chatId")));
            double amount = Double.parseDouble(String.valueOf(body.get("amount")));
            String mode = String.valueOf(body.getOrDefault("mode", "add"));

            double newBalance;
            if ("set".equalsIgnoreCase(mode)) {
                newBalance = db.setBalance(chatId, amount);
                bot.notifyUserBalanceUpdated(chatId, amount, newBalance, false);
            } else {
                newBalance = db.addBalance(chatId, amount);
                bot.notifyUserBalanceUpdated(chatId, amount, newBalance, true);
            }

            return ResponseEntity.ok(Map.of("success", true, "newBalance", newBalance));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ==================== 2. ORDERS (APPROVE / DELIVER / REJECT) ====================

    @PostMapping("/orders/deliver")
    public ResponseEntity<?> deliverPendingOrder(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int orderId = Integer.parseInt(String.valueOf(body.get("orderId")));
            String accountData = String.valueOf(body.get("accountData")).trim();
            if (accountData.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى إدخال بيانات الحساب للتسليم"));
            }

            DatabaseManager.Order updatedOrder = db.completePendingOrder(orderId, accountData);
            if (updatedOrder != null) {
                bot.notifyOrderManuallyDelivered(updatedOrder);
                return ResponseEntity.ok(Map.of("success", true));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "لم يتم العثور على الطلب"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/orders/reject")
    public ResponseEntity<?> rejectPendingOrder(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int orderId = Integer.parseInt(String.valueOf(body.get("orderId")));
            String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
            if (reason.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى كتابة سبب الرفض"));
            }
            DatabaseManager.Order rejected = db.rejectPendingOrder(orderId, reason);
            if (rejected != null) {
                bot.notifyOrderRejected(rejected);
                return ResponseEntity.ok(Map.of("success", true));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "لم يتم العثور على الطلب"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ==================== 3. PAYMENT METHODS ====================

    @PostMapping("/payment-methods")
    public ResponseEntity<?> createPaymentMethod(
            @RequestParam("name") String name,
            @RequestParam("currency") String currency,
            @RequestParam("instructions") String instructions,
            @RequestParam(value = "image", required = false) MultipartFile image,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "انتهت الجلسة، يرجى إعادة تسجيل الدخول للداشبورد"));
        }

        if (name == null || name.isBlank() || instructions == null || instructions.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى إدخال اسم الوسيلة والإجراءات"));
        }

        String savedIconPath = "";
        String customEmojiId = "";
        if (image != null && !image.isEmpty()) {
            try {
                File uploadDir = new File("uploads");
                if (!uploadDir.exists()) uploadDir.mkdirs();
                String originalName = image.getOriginalFilename();
                String ext = ".png";
                if (originalName != null && originalName.contains(".")) {
                    ext = originalName.substring(originalName.lastIndexOf("."));
                }
                String fileName = "pm_" + UUID.randomUUID().toString().substring(0, 8) + ext;
                Path targetPath = Paths.get("uploads", fileName);
                Files.write(targetPath, image.getBytes());
                savedIconPath = "uploads/" + fileName;
                try {
                    customEmojiId = emojiService.createCustomEmojiFromImage(targetPath.toFile());
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                System.err.println("Warning saving payment method icon: " + e.getMessage());
            }
        }

        int id = db.addPaymentMethod(name.trim(), currency.trim(), savedIconPath, customEmojiId, instructions.trim());
        if (id != -1) {
            return ResponseEntity.ok(Map.of("success", true, "id", id));
        }
        return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "خطأ في حفظ وسيلة الدفع في قاعدة البيانات"));
    }

    @PutMapping("/payment-methods/{id}")
    public ResponseEntity<?> updatePaymentMethod(
            @PathVariable int id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String currency = String.valueOf(body.getOrDefault("currency", "DZD")).trim();
        String instructions = String.valueOf(body.getOrDefault("instructions", "")).trim();
        boolean updated = db.updatePaymentMethod(id, name, currency, instructions);
        return ResponseEntity.ok(Map.of("success", updated));
    }

    @DeleteMapping("/payment-methods/{id}")
    public ResponseEntity<?> deletePaymentMethod(@PathVariable int id, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean deleted = db.deletePaymentMethod(id);
        return ResponseEntity.ok(Map.of("success", deleted));
    }

    // ==================== 4. TOPUP REQUESTS (APPROVE / REJECT) ====================

    @PostMapping("/topups/approve")
    public ResponseEntity<?> approveTopup(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int topupId = Integer.parseInt(String.valueOf(body.get("topupId")));
            DatabaseManager.TopupRequest approved = db.approveTopupRequest(topupId);
            if (approved != null) {
                bot.notifyTopupApproved(approved);
                return ResponseEntity.ok(Map.of("success", true));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "تعذر تأكيد الطلب"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/topups/reject")
    public ResponseEntity<?> rejectTopup(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int topupId = Integer.parseInt(String.valueOf(body.get("topupId")));
            String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
            if (reason.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى كتابة سبب الرفض"));
            }
            DatabaseManager.TopupRequest rejected = db.rejectTopupRequest(topupId, reason);
            if (rejected != null) {
                bot.notifyTopupRejected(rejected);
                return ResponseEntity.ok(Map.of("success", true));
            }
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "تعذر رفض الطلب"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ==================== 5. PRODUCTS & OFFERS ====================

    @PostMapping("/products")
    public ResponseEntity<?> createProduct(
            @RequestParam("category") String category,
            @RequestParam("name") String name,
            @RequestParam(value = "image", required = false) MultipartFile image,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى إدخال اسم المنتج"));
        }

        String savedImagePath = "";
        String customEmojiId = "";
        if (image != null && !image.isEmpty()) {
            try {
                File uploadDir = new File("uploads");
                if (!uploadDir.exists()) uploadDir.mkdirs();

                String originalName = image.getOriginalFilename();
                String ext = ".png";
                if (originalName != null && originalName.contains(".")) {
                    ext = originalName.substring(originalName.lastIndexOf("."));
                }
                String fileName = "logo_" + UUID.randomUUID().toString().substring(0, 8) + ext;
                Path targetPath = Paths.get("uploads", fileName);
                Files.write(targetPath, image.getBytes());
                savedImagePath = "uploads/" + fileName;

                customEmojiId = emojiService.createCustomEmojiFromImage(targetPath.toFile());
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "فشل رفع الشعار: " + e.getMessage()));
            }
        }

        int productId = db.addProductWithDetails(category, name.trim(), savedImagePath, customEmojiId);
        if (productId != -1) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "productId", productId,
                    "customEmojiId", customEmojiId
            ));
        }
        return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "فشل حفظ المنتج"));
    }

    @PostMapping("/products/logo")
    public ResponseEntity<?> updateProductLogo(
            @RequestParam("productId") int productId,
            @RequestParam("image") MultipartFile image,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى اختيار ملف الصورة"));
        }
        try {
            File uploadDir = new File("uploads");
            if (!uploadDir.exists()) uploadDir.mkdirs();

            String originalName = image.getOriginalFilename();
            String ext = ".png";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }
            String fileName = "logo_" + UUID.randomUUID().toString().substring(0, 8) + ext;
            Path targetPath = Paths.get("uploads", fileName);
            Files.write(targetPath, image.getBytes());
            String savedImagePath = "uploads/" + fileName;

            String customEmojiId = emojiService.createCustomEmojiFromImage(targetPath.toFile());
            boolean updated = db.updateProductLogo(productId, savedImagePath, customEmojiId);
            return ResponseEntity.ok(Map.of(
                    "success", updated,
                    "customEmojiId", customEmojiId
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable int id, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean deleted = db.deleteProduct(id);
        return ResponseEntity.ok(Map.of("success", deleted));
    }

    @PostMapping("/offers")
    public ResponseEntity<?> createOffer(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int productId = Integer.parseInt(String.valueOf(body.get("productId")));
            String name = String.valueOf(body.get("name")).trim();
            double price = Double.parseDouble(String.valueOf(body.get("price")));
            String description = String.valueOf(body.getOrDefault("description", "")).trim();
            boolean isAvailable = Boolean.parseBoolean(String.valueOf(body.getOrDefault("isAvailable", "true")));
            String stockLines = String.valueOf(body.getOrDefault("stockLines", ""));

            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى إدخال اسم العرض"));
            }

            int offerId = db.addOffer(productId, name, price, description, isAvailable, stockLines);
            return ResponseEntity.ok(Map.of("success", offerId != -1, "offerId", offerId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/offers/toggle")
    public ResponseEntity<?> toggleOfferAvailability(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int offerId = Integer.parseInt(String.valueOf(body.get("offerId")));
            boolean isAvailable = Boolean.parseBoolean(String.valueOf(body.get("isAvailable")));

            boolean updated = db.updateOfferAvailability(offerId, isAvailable);
            int notifiedUsers = 0;
            if (updated && isAvailable) {
                notifiedUsers = bot.notifyWaitingUsersOfferAvailable(offerId);
            }
            return ResponseEntity.ok(Map.of(
                    "success", updated,
                    "isAvailable", isAvailable,
                    "notifiedUsers", notifiedUsers
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/offers/stock")
    public ResponseEntity<?> addOfferStock(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int offerId = Integer.parseInt(String.valueOf(body.get("offerId")));
            String stockLines = String.valueOf(body.getOrDefault("stockLines", ""));
            int addedCount = db.addOfferStockBatch(offerId, stockLines);
            return ResponseEntity.ok(Map.of("success", true, "addedCount", addedCount));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/offers/{id}")
    public ResponseEntity<?> updateOffer(
            @PathVariable int id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String name = String.valueOf(body.get("name")).trim();
            double price = Double.parseDouble(String.valueOf(body.get("price")));
            String description = String.valueOf(body.getOrDefault("description", "")).trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى إدخال اسم العرض"));
            }
            boolean updated = db.updateOfferDetails(id, name, price, description);
            return ResponseEntity.ok(Map.of("success", updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<?> updateProduct(
            @PathVariable int id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String category = String.valueOf(body.get("category")).trim();
            String name = String.valueOf(body.get("name")).trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى إدخال اسم المنتج"));
            }
            boolean updated = db.updateProductDetails(id, category, name);
            return ResponseEntity.ok(Map.of("success", updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/offers/{id}")
    public ResponseEntity<?> deleteOffer(@PathVariable int id, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean deleted = db.deleteOffer(id);
        return ResponseEntity.ok(Map.of("success", deleted));
    }

    // ==================== 6. HEALTH & BACKUP ====================

    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "Moh Service Bot"));
    }

    @GetMapping("/backup/download")
    public ResponseEntity<Resource> downloadDatabaseBackup(HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        File dbFile = new File("moh_service.db");
        if (!dbFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(dbFile);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"moh_service.db\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PostMapping("/backup/restore")
    public ResponseEntity<?> restoreDatabaseBackup(@RequestParam("file") MultipartFile file, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "يرجى اختيار ملف قاعدة البيانات moh_service.db"));
        }
        try {
            Files.copy(file.getInputStream(), Paths.get("moh_service.db"), StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
