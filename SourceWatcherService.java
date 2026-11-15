package com.mohservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SourceWatcherService {

    private final DatabaseManager db;
    private final MohServiceBot bot;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Holds temporary login state between send_code and verify_code
    private String pendingPhoneCodeHash = "";
    private String pendingTempSession = "";
    private boolean syncInProgress = false;

    public SourceWatcherService(DatabaseManager db, MohServiceBot bot) {
        this.db = db;
        this.bot = bot;
    }

    @PostConstruct
    public void initScheduledWatcher() {
        // Check every 1 minute if it's time to run periodic sync
        scheduler.scheduleWithFixedDelay(this::checkAndRunPeriodicSync, 2, 5, TimeUnit.MINUTES);
    }

    private void checkAndRunPeriodicSync() {
        try {
            DatabaseManager.WatcherConfig cfg = db.getWatcherConfig();
            if (!cfg.isConnected || cfg.sessionString.isBlank()) return;
            List<DatabaseManager.Offer> syncOffers = db.getAutoSyncOffers();
            if (syncOffers.isEmpty()) return;
            runAutoSyncNow();
        } catch (Exception e) {
            System.err.println("Periodic sync error: " + e.getMessage());
        }
    }

    public Map<String, Object> sendLoginCode(String apiId, String apiHash, String phone, int intervalMinutes) {
        db.saveWatcherCredentials(apiId, apiHash, phone, intervalMinutes);
        Map<String, Object> req = Map.of(
                "action", "send_code",
                "apiId", apiId.trim(),
                "apiHash", apiHash.trim(),
                "phone", phone.trim()
        );
        JsonNode resp = runPythonWatcher(req, 45);
        if (resp != null && resp.path("success").asBoolean()) {
            this.pendingPhoneCodeHash = resp.path("phoneCodeHash").asText("");
            this.pendingTempSession = resp.path("tempSession").asText("");
            return Map.of("success", true, "message", "تم إرسال كود التحقق إلى حسابك في تلغرام!");
        }
        String err = resp != null ? resp.path("message").asText("تعذر إرسال الكود") : "فشل تشغيل محرك Telethon";
        return Map.of("success", false, "message", err);
    }

    public Map<String, Object> verifyLoginCode(String code, String password2FA) {
        DatabaseManager.WatcherConfig cfg = db.getWatcherConfig();
        Map<String, Object> req = new HashMap<>();
        req.put("action", "verify_code");
        req.put("apiId", cfg.apiId);
        req.put("apiHash", cfg.apiHash);
        req.put("phone", cfg.phone);
        req.put("code", code.trim());
        req.put("password", password2FA != null ? password2FA.trim() : "");
        req.put("phoneCodeHash", pendingPhoneCodeHash);
        req.put("tempSession", pendingTempSession);

        JsonNode resp = runPythonWatcher(req, 45);
        if (resp != null && resp.path("success").asBoolean()) {
            String sessionString = resp.path("sessionString").asText("");
            db.saveWatcherSession(sessionString, true);
            return Map.of("success", true, "message", "✅ تم ربط حساب المراقب بنجاح!");
        }
        boolean needPass = resp != null && resp.path("needPassword").asBoolean(false);
        String err = resp != null ? resp.path("message").asText("فشل التحقق من الكود") : "فشل التحقق";
        return Map.of("success", false, "needPassword", needPass, "message", err);
    }

    public synchronized Map<String, Object> runAutoSyncNow() {
        if (syncInProgress) {
            return Map.of("success", false, "message", "عملية الفحص والمزامنة قيد التشغيل حالياً، يرجى الانتظار...");
        }
        DatabaseManager.WatcherConfig cfg = db.getWatcherConfig();
        if (!cfg.isConnected || cfg.sessionString.isBlank()) {
            return Map.of("success", false, "message", "يرجى تسجيل دخول حساب التلغرام المراقب أولاً من إعدادات المزامنة.");
        }

        List<DatabaseManager.Offer> offers = db.getAutoSyncOffers();
        if (offers.isEmpty()) {
            return Map.of("success", false, "message", "لا توجد عروض مفعّل لها خيار المزامنة التلقائية حالياً.");
        }

        syncInProgress = true;
        try {
            List<Map<String, Object>> offerPayloads = new ArrayList<>();
            for (DatabaseManager.Offer o : offers) {
                offerPayloads.add(Map.of(
                        "offerId", o.id,
                        "sourceBots", o.sourceBots,
                        "sourceProductBtn", o.sourceProductBtn,
                        "sourceOfferKeyword", o.sourceOfferKeyword
                ));
            }

            Map<String, Object> req = Map.of(
                    "action", "sync",
                    "apiId", cfg.apiId,
                    "apiHash", cfg.apiHash,
                    "sessionString", cfg.sessionString,
                    "offers", offerPayloads
            );

            JsonNode resp = runPythonWatcher(req, 180);
            if (resp == null || !resp.path("success").asBoolean()) {
                String err = resp != null ? resp.path("message").asText("فشل الاتصال بالمصادر") : "انتهت مهلة الفحص";
                db.saveWatcherSyncLog("❌ " + err);
                return Map.of("success", false, "message", err);
            }

            StringBuilder logBuilder = new StringBuilder();
            int updatedCount = 0;
            JsonNode results = resp.path("results");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    int offerId = item.path("offerId").asInt();
                    boolean found = item.path("found").asBoolean(false);
                    String itemLog = item.path("log").asText("");
                    DatabaseManager.Offer existing = db.getOfferById(offerId);
                    if (existing == null) continue;

                    if (found) {
                        double srcPrice = item.path("sourcePrice").asDouble();
                        boolean availableInSource = item.path("available").asBoolean(true);
                        String customEmojiId = item.path("customEmojiId").asText("");

                        double newSellPrice = Math.round((srcPrice + existing.profitMargin) * 100.0) / 100.0;
                        boolean wasUnavailable = !existing.isAvailable;

                        db.applySyncedOfferResult(offerId, srcPrice, newSellPrice, availableInSource);
                        if (!customEmojiId.isEmpty()) {
                            db.updateProductCustomEmojiIfEmpty(existing.productId, customEmojiId);
                        }

                        // If it became available, notify waiting users automatically!
                        if (wasUnavailable && availableInSource) {
                            bot.notifyWaitingUsersOfferAvailable(offerId);
                        }

                        updatedCount++;
                        logBuilder.append("✅ [").append(existing.productName).append(" - ").append(existing.name).append("]: ")
                                  .append("سعر المصدر $").append(srcPrice)
                                  .append(" + ربح $").append(existing.profitMargin)
                                  .append(" ⬅️ السعر الجديد في بوتك: $").append(newSellPrice)
                                  .append(" (").append(Math.round(newSellPrice * DatabaseManager.DZD_EXCHANGE_RATE)).append(" DZD) ")
                                  .append(availableInSource ? "🟢 متوفر" : "🔴 غير متوفر")
                                  .append(" [").append(itemLog).append("]\n");
                    } else {
                        logBuilder.append("⚠️ [").append(existing.productName).append(" - ").append(existing.name).append("]: ")
                                  .append(itemLog).append("\n");
                    }
                }
            }

            String finalLog = logBuilder.toString().trim();
            db.saveWatcherSyncLog(finalLog);
            return Map.of("success", true, "updatedCount", updatedCount, "log", finalLog);
        } finally {
            syncInProgress = false;
        }
    }

    private JsonNode runPythonWatcher(Map<String, Object> inputData, int timeoutSeconds) {
        try {
            File scriptFile = new File("source_watcher.py");
            if (!scriptFile.exists()) {
                System.err.println("source_watcher.py not found!");
                return null;
            }
            String pythonCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, scriptFile.getAbsolutePath());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(inputData));
                os.flush();
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (stdout.isEmpty()) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return objectMapper.createObjectNode()
                        .put("success", false)
                        .put("message", stderr.isEmpty() ? "Empty response from python" : stderr);
            }
            // Parse the last line of stdout in case of warnings
            String[] lines = stdout.split("\\r?\\n");
            String lastLine = lines[lines.length - 1].trim();
            return objectMapper.readTree(lastLine);
        } catch (Exception e) {
            System.err.println("Error executing python watcher: " + e.getMessage());
            return objectMapper.createObjectNode()
                    .put("success", false)
                    .put("message", e.getMessage());
        }
    }
}
