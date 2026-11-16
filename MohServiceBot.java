package com.mohservice;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;
import java.util.concurrent.*;

@Component
public class MohServiceBot extends TelegramLongPollingBot {

    public static final String BOT_TOKEN = "9058888272:AAFsCFbiZJWKvu328D1_wY4rEtQebAJAfrY";
    public static final String BOT_USERNAME = "MohServiceBot";
    public static final long ADMIN_CHAT_ID = 8780415382L;

    private final DatabaseManager db;
    private final TelegramEmojiService emojiService;

    // 20-minute payment session timer scheduler
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, UserPaymentSession> userSessions = new ConcurrentHashMap<>();

    public enum SessionType {
        ENTERING_TOPUP_AMOUNT,
        WAITING_TOPUP_PROOF,
        WAITING_ORDER_PROOF
    }

    public static class UserPaymentSession {
        public SessionType type;
        public String currency; // "USD" or "DZD"
        public int recordId;    // topupId or orderId
        public ScheduledFuture<?> expiryTask;

        public UserPaymentSession(SessionType type, String currency, int recordId, ScheduledFuture<?> expiryTask) {
            this.type = type;
            this.currency = currency;
            this.recordId = recordId;
            this.expiryTask = expiryTask;
        }
    }

    public static class RichButton {
        public String text;
        public String callbackData;
        public String url;
        public String iconCustomEmojiId;
        public String style; // "success", "danger", "primary", or null

        public RichButton(String text, String callbackData, String iconCustomEmojiId, String style) {
            this.text = text;
            this.callbackData = callbackData;
            this.iconCustomEmojiId = iconCustomEmojiId;
            this.style = style;
        }
    }

    public MohServiceBot(DatabaseManager db, TelegramEmojiService emojiService) {
        super(BOT_TOKEN);
        this.db = db;
        this.emojiService = emojiService;
    }

    @Override
    public String getBotUsername() {
        return BOT_USERNAME;
    }

    public static String getCategoryName(String code) {
        return switch (code) {
            case "1" -> "🎬 اشتراكات الترفيه";
            case "2" -> "🤖 اشتراكات الذكاء الاصطناعي";
            case "3" -> "📱 اشتراكات السوشيال ميديا";
            default -> "📦 باقات متنوعة";
        };
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleIncomingMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            System.err.println("Error processing update: " + e.getMessage());
        }
    }

    private void clearUserSession(long chatId) {
        UserPaymentSession old = userSessions.remove(chatId);
        if (old != null && old.expiryTask != null) {
            old.expiryTask.cancel(false);
        }
    }

    private void handleIncomingMessage(Message message) {
        long chatId = message.getChatId();
        String text = message.hasText() ? message.getText().trim() : (message.getCaption() != null ? message.getCaption().trim() : "");
        String username = message.getFrom().getUserName();
        String firstName = message.getFrom().getFirstName();

        db.getOrCreateUser(chatId, username, firstName);

        if (text.equals("/start") || text.startsWith("/start ")) {
            clearUserSession(chatId);
            sendStartMessage(chatId, firstName);
            return;
        }

        // Admin Commands (only work for ADMIN_CHAT_ID)
        if (chatId == ADMIN_CHAT_ID) {
            if (text.startsWith("/addprod")) {
                handleAddProd(chatId, text);
                return;
            } else if (text.startsWith("/addstock")) {
                handleAddStock(chatId, text);
                return;
            } else if (text.startsWith("/addbal")) {
                handleAddBal(chatId, text);
                return;
            }
        } else if (text.startsWith("/addprod") || text.startsWith("/addstock") || text.startsWith("/addbal")) {
            sendHtmlMessage(chatId, "⛔ <b>عذراً، هذا الأمر مخصص لإدارة البوت فقط.</b>", null);
            return;
        }

        // Check active payment session
        UserPaymentSession session = userSessions.get(chatId);
        if (session != null) {
            switch (session.type) {
                case ENTERING_TOPUP_AMOUNT -> handleTopupAmountInput(chatId, text, session.currency);
                case WAITING_TOPUP_PROOF -> handleTopupProofReceived(message, session);
                case WAITING_ORDER_PROOF -> handleOrderProofReceived(message, session);
            }
            return;
        }
    }

    // ==================== WALLET TOPUP & DIRECT PAYMENT FLOWS ====================

    private void handleTopupAmountInput(long chatId, String text, String currency) {
        double enteredAmount;
        try {
            enteredAmount = Double.parseDouble(text.replace(",", ".").trim());
            if (enteredAmount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>يرجى إدخال مبلغ رقمي صحيح أكبر من الصفر (مثال: <code>10</code> أو <code>2600</code>):</b>", getBackToMainKeyboard());
            return;
        }

        clearUserSession(chatId);

        List<DatabaseManager.PaymentMethod> methods = db.getPaymentMethodsByCurrency(currency);
        if (methods.isEmpty()) {
            sendHtmlMessage(chatId,
                    "⚠️ <b>لا توجد وسائل دفع متاحة حالياً لعملة " + escapeHtml(currency) + ".</b>\n" +
                    "يرجى المحاولة لاحقاً أو التواصل مع الدعم الفني.",
                    getBackToMainKeyboard());
            return;
        }

        double amountUsd = "DZD".equalsIgnoreCase(currency)
                ? (enteredAmount / DatabaseManager.DZD_EXCHANGE_RATE)
                : enteredAmount;

        String currSymbol = "DZD".equalsIgnoreCase(currency) ? "DZD (د.ج)" : "USD ($)";
        String msg = "💳 <b>اختر وسيلة الدفع المناسبة لك:</b>\n\n" +
                     "💵 <b>المبلغ المراد شحنه:</b> <code>" + formatMoney(enteredAmount) + " " + currSymbol + "</code>\n" +
                     ("DZD".equalsIgnoreCase(currency)
                             ? "💰 <b>الرصيد الذي سيضاف لمحفظتك بالدولار:</b> <code>$" + formatMoney(amountUsd) + "</code> <i>(بسعر صرف 1$ = 260 د.ج)</i>\n\n"
                             : "\n") +
                     "<blockquote>اختر بوابة الدفع أدناه لعرض معلومات الحساب وإجراءات التحقق:</blockquote>";

        List<List<RichButton>> rows = new ArrayList<>();
        for (DatabaseManager.PaymentMethod pm : methods) {
            String emojiId = (pm.customEmojiId != null && !pm.customEmojiId.isBlank()) ? pm.customEmojiId.trim() : null;
            List<RichButton> r = new ArrayList<>();
            r.add(new RichButton("💳 " + pm.name, "topup_pm_" + pm.id + "_" + formatMoney(enteredAmount), emojiId, null));
            rows.add(r);
        }

        List<RichButton> backRow = new ArrayList<>();
        backRow.add(new RichButton("🔙 إلغاء والرجوع للمحفظة", "wallet", null, null));
        rows.add(backRow);

        sendMessageWithRichButtons(chatId, msg, rows);
    }

    private void startTopupWithMethod(long chatId, int messageId, boolean isPhotoMessage, int methodId, double enteredAmount) {
        DatabaseManager.PaymentMethod pm = db.getPaymentMethodById(methodId);
        if (pm == null) {
            editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, "❌ وسيلة الدفع غير متوفرة.", getBackToMainKeyboard());
            return;
        }

        double amountUsd = "DZD".equalsIgnoreCase(pm.currency)
                ? (enteredAmount / DatabaseManager.DZD_EXCHANGE_RATE)
                : enteredAmount;

        int topupId = db.createTopupRequest(chatId, pm.currency, enteredAmount, amountUsd, pm.id, pm.name);
        if (topupId == -1) {
            editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, "❌ حدث خطأ أثناء إنشاء طلب الشحن.", getBackToMainKeyboard());
            return;
        }

        // Schedule 20-minute expiration
        clearUserSession(chatId);
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            DatabaseManager.TopupRequest expired = db.expireTopupIfWaiting(topupId);
            if (expired != null) {
                userSessions.remove(chatId);
                sendHtmlMessage(chatId,
                        "⏰ <b>انتهت مهلة الـ 20 دقيقة!</b>\n\n" +
                        "تم إلغاء عملية شحن المحفظة رقم <code>#" + topupId + "</code> تلقائياً بسبب انتهاء الوقت المحدد دون إرسال إثبات الدفع.\n\n" +
                        "يمكنك إنشاء طلب جديد في أي وقت من قسم المحفظة.",
                        getBackToMainKeyboard());
            }
        }, 20, TimeUnit.MINUTES);

        userSessions.put(chatId, new UserPaymentSession(SessionType.WAITING_TOPUP_PROOF, pm.currency, topupId, task));

        String currLabel = "DZD".equalsIgnoreCase(pm.currency) ? "د.ج (DZD)" : "$ (USD)";
        String text = "🧾 <b>طلب شحن محفظة رقم:</b> <code>#" + topupId + "</code>\n" +
                      "💳 <b>وسيلة الدفع:</b> " + escapeHtml(pm.name) + "\n" +
                      "💰 <b>المبلغ الواجب إرساله:</b> <code>" + formatMoney(enteredAmount) + " " + currLabel + "</code>\n" +
                      ("DZD".equalsIgnoreCase(pm.currency)
                              ? "💵 <b>يُشحن في المحفظة:</b> <code>$" + formatMoney(amountUsd) + "</code>\n"
                              : "") +
                      "⏳ <b>مهلة الإرسال:</b> <code>20 دقيقة</code>\n\n" +
                      "⚠️ <b>تحذير هام:</b>\n" +
                      "<blockquote>يرجى إتمام الدفع وإرسال الإثبات خلال <b>20 دقيقة</b>، وفي حالة انتهاء الوقت ستُلغى العملية تلقائياً!</blockquote>\n\n" +
                      "📋 <b>الإجراءات ومعلومات الحساب للدفع:</b>\n" +
                      "<blockquote>" + escapeHtml(pm.instructions) + "</blockquote>\n\n" +
                      "📸 <b>بعد إتمام الدفع، أرسل الآن هنا في المحادثة صورة وصل الدفع (الفاتورة / الإيصال) أو رقم العملية:</b>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r = new ArrayList<>();
        InlineKeyboardButton cancelBtn = new InlineKeyboardButton("❌ إلغاء العملية");
        cancelBtn.setCallbackData("cancel_payment");
        r.add(cancelBtn);
        rows.add(r);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void handleTopupProofReceived(Message message, UserPaymentSession session) {
        long chatId = message.getChatId();
        String proofText = message.hasText() ? message.getText().trim() : (message.getCaption() != null ? message.getCaption().trim() : "");
        String proofImage = "";

        if (message.hasPhoto()) {
            List<PhotoSize> photos = message.getPhoto();
            PhotoSize largest = photos.get(photos.size() - 1);
            proofImage = emojiService.downloadTelegramFileToUploads(largest.getFileId(), "topup_" + session.recordId);
        } else if (message.hasDocument()) {
            proofImage = emojiService.downloadTelegramFileToUploads(message.getDocument().getFileId(), "topup_" + session.recordId);
        }

        if (proofText.isEmpty() && proofImage.isEmpty()) {
            sendHtmlMessage(chatId, "⚠️ <b>يرجى إرسال صورة الفاتورة/الإيصال أو كتابة رقم/بيانات عملية الدفع:</b>", null);
            return;
        }

        clearUserSession(chatId);
        boolean saved = db.attachTopupProof(session.recordId, proofText, proofImage);
        if (saved) {
            sendHtmlMessage(chatId,
                    "✅ <b>تم استلام إثبات الدفع بنجاح!</b>\n\n" +
                    "<blockquote>سنقوم بالتحقق من هذه العملية وشحن حسابك عند التأكيد. نشكرك على ثقتك بنا! 🌟</blockquote>",
                    getBackToMainKeyboard());
        } else {
            sendHtmlMessage(chatId, "⚠️ <b>انتهت صلاحية هذا الطلب أو تم إلغاؤه مسبقاً.</b>", getBackToMainKeyboard());
        }
    }

    private void showDirectPaymentCurrencyChoice(long chatId, int messageId, boolean isPhotoMessage, int offerId) {
        DatabaseManager.Offer offer = db.getOfferById(offerId);
        if (offer == null) return;

        double priceUsd = offer.price;
        double priceDzd = Math.round(priceUsd * DatabaseManager.DZD_EXCHANGE_RATE);

        String text = "💳 <b>الدفع المباشر لشراء العرض:</b>\n\n" +
                      "📦 <b>العرض:</b> " + escapeHtml(offer.productName + " - " + offer.name) + "\n" +
                      "💵 <b>السعر بالدولار:</b> <code>$" + formatMoney(priceUsd) + " USD</code>\n" +
                      "🇩🇿 <b>السعر بالدينار الجزائري:</b> <code>" + formatMoney(priceDzd) + " DZD</code>\n\n" +
                      "<blockquote>اختر العملة التي ترغب بالدفع بها لعرض وسائل الدفع المتوفرة:</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> r1 = new ArrayList<>();
        InlineKeyboardButton btnUsd = new InlineKeyboardButton("💵 الدفع بالدولار ($" + formatMoney(priceUsd) + " USD)");
        btnUsd.setCallbackData("dir_curr_USD_" + offerId);
        r1.add(btnUsd);

        List<InlineKeyboardButton> r2 = new ArrayList<>();
        InlineKeyboardButton btnDzd = new InlineKeyboardButton("🇩🇿 الدفع بالدينار (" + formatMoney(priceDzd) + " DZD)");
        btnDzd.setCallbackData("dir_curr_DZD_" + offerId);
        r2.add(btnDzd);

        List<InlineKeyboardButton> backRow = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 رجوع لتفاصيل العرض");
        backBtn.setCallbackData("offer_" + offerId);
        backRow.add(backBtn);

        rows.add(r1);
        rows.add(r2);
        rows.add(backRow);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void showDirectPaymentMethodsForOffer(long chatId, int messageId, boolean isPhotoMessage, String currency, int offerId) {
        DatabaseManager.Offer offer = db.getOfferById(offerId);
        if (offer == null) return;

        List<DatabaseManager.PaymentMethod> methods = db.getPaymentMethodsByCurrency(currency);
        if (methods.isEmpty()) {
            editOrReplaceWithHtml(chatId, messageId, isPhotoMessage,
                    "⚠️ <b>لا توجد وسائل دفع مضافة حالياً لعملة " + escapeHtml(currency) + ".</b>",
                    getBackToMainKeyboard());
            return;
        }

        double amountToPay = "DZD".equalsIgnoreCase(currency)
                ? Math.round(offer.price * DatabaseManager.DZD_EXCHANGE_RATE)
                : offer.price;
        String currLabel = "DZD".equalsIgnoreCase(currency) ? "DZD (د.ج)" : "USD ($)";

        String text = "💳 <b>اختر وسيلة الدفع لإتمام الشراء:</b>\n\n" +
                      "📦 <b>العرض:</b> " + escapeHtml(offer.productName + " - " + offer.name) + "\n" +
                      "💰 <b>المبلغ المطلوب دفعه:</b> <code>" + formatMoney(amountToPay) + " " + currLabel + "</code>\n\n" +
                      "<blockquote>اختر وسيلة الدفع أدناه لعرض خطوات الدفع وإرسال الإيصال:</blockquote>";

        List<List<RichButton>> rows = new ArrayList<>();
        for (DatabaseManager.PaymentMethod pm : methods) {
            String emojiId = (pm.customEmojiId != null && !pm.customEmojiId.isBlank()) ? pm.customEmojiId.trim() : null;
            List<RichButton> r = new ArrayList<>();
            r.add(new RichButton("💳 " + pm.name, "dir_pm_" + pm.id + "_" + offerId, emojiId, null));
            rows.add(r);
        }

        List<RichButton> backRow = new ArrayList<>();
        backRow.add(new RichButton("🔙 رجوع لاختيار العملة", "pay_direct_" + offerId, null, null));
        rows.add(backRow);

        editOrReplaceWithRichButtons(chatId, messageId, isPhotoMessage, text, rows);
    }

    private void startDirectOrderPayment(long chatId, int messageId, boolean isPhotoMessage, int methodId, int offerId) {
        DatabaseManager.PaymentMethod pm = db.getPaymentMethodById(methodId);
        DatabaseManager.Offer offer = db.getOfferById(offerId);
        if (pm == null || offer == null) {
            editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, "❌ العرض أو وسيلة الدفع غير متوفرة.", getBackToMainKeyboard());
            return;
        }

        double amountToPay = "DZD".equalsIgnoreCase(pm.currency)
                ? Math.round(offer.price * DatabaseManager.DZD_EXCHANGE_RATE)
                : offer.price;

        int orderId = db.createDirectPaymentOrder(chatId, offerId, pm.currency, amountToPay, pm.name);
        if (orderId == -1) {
            editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, "❌ حدث خطأ أثناء إنشاء الطلب.", getBackToMainKeyboard());
            return;
        }

        clearUserSession(chatId);
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            DatabaseManager.Order expired = db.expireOrderIfWaiting(orderId);
            if (expired != null) {
                userSessions.remove(chatId);
                sendHtmlMessage(chatId,
                        "⏰ <b>انتهت مهلة الـ 20 دقيقة!</b>\n\n" +
                        "تم إلغاء طلب الشراء رقم <code>#" + orderId + "</code> (" + escapeHtml(expired.productName) + ") تلقائياً بسبب انتهاء الوقت دون إرسال إثبات الدفع.",
                        getBackToMainKeyboard());
            }
        }, 20, TimeUnit.MINUTES);

        userSessions.put(chatId, new UserPaymentSession(SessionType.WAITING_ORDER_PROOF, pm.currency, orderId, task));

        String currLabel = "DZD".equalsIgnoreCase(pm.currency) ? "د.ج (DZD)" : "$ (USD)";
        String text = "🧾 <b>طلب شراء مباشر رقم:</b> <code>#" + orderId + "</code>\n" +
                      "📦 <b>العرض:</b> " + escapeHtml(offer.productName + " - " + offer.name) + "\n" +
                      "💳 <b>وسيلة الدفع:</b> " + escapeHtml(pm.name) + "\n" +
                      "💰 <b>المبلغ الواجب دفعه:</b> <code>" + formatMoney(amountToPay) + " " + currLabel + "</code>\n" +
                      "⏳ <b>مهلة الإرسال:</b> <code>20 دقيقة</code>\n\n" +
                      "⚠️ <b>تحذير هام:</b>\n" +
                      "<blockquote>يرجى إتمام الدفع وإرسال الإثبات خلال <b>20 دقيقة</b>، وفي حالة انتهاء الوقت ستُلغى العملية تلقائياً!</blockquote>\n\n" +
                      "📋 <b>الإجراءات ومعلومات الحساب للدفع:</b>\n" +
                      "<blockquote>" + escapeHtml(pm.instructions) + "</blockquote>\n\n" +
                      "📸 <b>بعد إتمام الدفع، أرسل الآن هنا في المحادثة صورة وصل الدفع (الفاتورة / الإيصال) أو رقم العملية:</b>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r = new ArrayList<>();
        InlineKeyboardButton cancelBtn = new InlineKeyboardButton("❌ إلغاء العملية");
        cancelBtn.setCallbackData("cancel_payment");
        r.add(cancelBtn);
        rows.add(r);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void handleOrderProofReceived(Message message, UserPaymentSession session) {
        long chatId = message.getChatId();
        String proofText = message.hasText() ? message.getText().trim() : (message.getCaption() != null ? message.getCaption().trim() : "");
        String proofImage = "";

        if (message.hasPhoto()) {
            List<PhotoSize> photos = message.getPhoto();
            PhotoSize largest = photos.get(photos.size() - 1);
            proofImage = emojiService.downloadTelegramFileToUploads(largest.getFileId(), "order_" + session.recordId);
        } else if (message.hasDocument()) {
            proofImage = emojiService.downloadTelegramFileToUploads(message.getDocument().getFileId(), "order_" + session.recordId);
        }

        if (proofText.isEmpty() && proofImage.isEmpty()) {
            sendHtmlMessage(chatId, "⚠️ <b>يرجى إرسال صورة الفاتورة/الإيصال أو كتابة رقم/بيانات عملية الدفع:</b>", null);
            return;
        }

        clearUserSession(chatId);
        boolean saved = db.attachOrderPaymentProof(session.recordId, proofText, proofImage);
        if (saved) {
            sendHtmlMessage(chatId,
                    "⏳ <b>تم استلام طلبك وإثبات الدفع بنجاح!</b>\n\n" +
                    "🧾 <b>رقم الطلب:</b> <code>#" + session.recordId + "</code>\n" +
                    "<blockquote>سنقوم بالتحقق من عملية الدفع وإرسال طلبك لك في أقرب وقت ممكن، نشكرك على صبرك ❤️</blockquote>",
                    getBackToMainKeyboard());
        } else {
            sendHtmlMessage(chatId, "⚠️ <b>انتهت صلاحية هذا الطلب أو تم إلغاؤه مسبقاً.</b>", getBackToMainKeyboard());
        }
    }

    // ==================== ADMIN COMMANDS ====================

    private void handleAddProd(long chatId, String text) {
        String payload = text.replaceFirst("^/addprod\\s*", "").trim();
        String[] parts = payload.split("\\|", 2);
        if (parts.length < 2) {
            sendHtmlMessage(chatId,
                    "⚠️ <b>صيغة الأمر غير صحيحة!</b>\n\n" +
                    "الاستخدام الصحيح:\n" +
                    "<code>/addprod الاسم | السعر</code>", null);
            return;
        }

        String name = parts[0].trim();
        double price;
        try {
            price = Double.parseDouble(parts[1].trim());
            if (price < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>السعر غير صالح!</b>", null);
            return;
        }

        int productId = db.addProduct(name, price);
        if (productId != -1) {
            sendHtmlMessage(chatId, "✅ <b>تم إضافة المنتج بنجاح!</b> (#" + productId + ")", null);
        }
    }

    private void handleAddStock(long chatId, String text) {
        String payload = text.replaceFirst("^/addstock\\s*", "").trim();
        String[] parts = payload.split("\\|", 2);
        if (parts.length < 2) {
            sendHtmlMessage(chatId, "⚠️ <b>الاستخدام:</b> <code>/addstock رقم_المنتج | بيانات_الحساب</code>", null);
            return;
        }
        try {
            int productId = Integer.parseInt(parts[0].trim());
            String accountData = parts[1].trim();
            if (db.addStock(productId, accountData)) {
                sendHtmlMessage(chatId, "✅ <b>تمت إضافة المخزون بنجاح!</b>", null);
            } else {
                sendHtmlMessage(chatId, "❌ <b>لم يتم العثور على المنتج.</b>", null);
            }
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>رقم المنتج غير صالح!</b>", null);
        }
    }

    private void handleAddBal(long chatId, String text) {
        String payload = text.replaceFirst("^/addbal\\s*", "").trim();
        String[] parts = payload.split("\\s+");
        if (parts.length < 2) {
            sendHtmlMessage(chatId, "⚠️ <b>الاستخدام:</b> <code>/addbal آيدي_العميل المبلغ</code>", null);
            return;
        }
        try {
            long targetUserId = Long.parseLong(parts[0].trim());
            double amount = Double.parseDouble(parts[1].trim());
            double newBalance = db.addBalance(targetUserId, amount);
            if (newBalance >= 0) {
                sendHtmlMessage(chatId, "✅ <b>تم شحن رصيد العميل بنجاح!</b>", null);
                notifyUserBalanceUpdated(targetUserId, amount, newBalance, true);
            }
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>تأكد من صحة الآيدي والمبلغ!</b>", null);
        }
    }

    // ==================== PUBLIC NOTIFICATIONS CALLED FROM WEB PANEL ====================

    public void notifyUserBalanceUpdated(long targetUserId, double amount, double newBalance, boolean isAddition) {
        String actionText = isAddition
                ? "تم إضافة مبلغ <code>$" + formatMoney(amount) + "</code> إلى محفظتك بنجاح."
                : "تم تحديث رصيد محفظتك من قبل الإدارة.";
        String clientNotification =
                "🎉 <b>إشعار تحديث الرصيد!</b>\n\n" +
                actionText + "\n" +
                "💰 <b>رصيدك الحالي:</b> <code>$" + formatMoney(newBalance) + "</code>\n\n" +
                "شكراً لاستخدامك <b>Moh Service</b>! 🌟";
        sendHtmlMessage(targetUserId, clientNotification, getBackToMainKeyboard());
    }

    public void notifyTopupApproved(DatabaseManager.TopupRequest req) {
        if (req == null) return;
        DatabaseManager.User user = db.getOrCreateUser(req.userId, null, null);
        String currLabel = "DZD".equalsIgnoreCase(req.currency) ? "DZD" : "USD";
        String msg = "✅ <b>تم التحقق من الدفع وشحن حسابك بنجاح!</b>\n\n" +
                     "🧾 <b>رقم عملية الشحن:</b> <code>#" + req.id + "</code>\n" +
                     "💳 <b>وسيلة الدفع:</b> " + escapeHtml(req.paymentMethodName) + "\n" +
                     "💵 <b>المبلغ المدفوع:</b> <code>" + formatMoney(req.amountPaid) + " " + currLabel + "</code>\n" +
                     "➕ <b>الرصيد المضاف للمحفظة:</b> <code>$" + formatMoney(req.amountUsd) + "</code>\n" +
                     "💰 <b>رصيد محفظتك الحالي:</b> <code>$" + formatMoney(user.balance) + "</code>\n\n" +
                     "شكراً لثقتك في <b>Moh Service</b>! 🌟";
        sendHtmlMessage(req.userId, msg, getBackToMainKeyboard());
    }

    public void notifyTopupRejected(DatabaseManager.TopupRequest req) {
        if (req == null) return;
        String reason = (req.rejectionReason != null && !req.rejectionReason.isBlank())
                ? escapeHtml(req.rejectionReason)
                : "بيانات الإيصال غير مطابقة";
        String msg = "❌ <b>فشل التحقق من عملية الدفع! (طلب شحن #" + req.id + ")</b>\n\n" +
                     "📌 <b>السبب:</b>\n" +
                     "<blockquote>" + reason + "</blockquote>\n\n" +
                     "💡 <i>في حالة ترى أنه حدث خطأ تواصل مع الدعم الفني.</i>";
        sendHtmlMessage(req.userId, msg, getSupportAndMainKeyboard());
    }

    public void notifyOrderManuallyDelivered(DatabaseManager.Order order) {
        if (order == null) return;
        DatabaseManager.User user = db.getOrCreateUser(order.userId, null, null);
        String paymentNote = "WALLET".equalsIgnoreCase(order.paymentType)
                ? "💰 <b>تم خصم:</b> <code>$" + formatMoney(order.price) + "</code> من محفظتك (رصيدك المتبقي: <code>$" + formatMoney(user.balance) + "</code>)\n"
                : "💳 <b>طريقة الدفع:</b> " + escapeHtml(order.paymentMethodName) + " (<code>" + formatMoney(order.amountToPay) + " " + escapeHtml(order.currency) + "</code>)\n";

        String msg = "✅ <b>تم تأكيد طلبك وتسليم الحساب بنجاح!</b>\n\n" +
                     "🧾 <b>رقم الطلب:</b> <code>#" + order.id + "</code>\n" +
                     "📦 <b>المنتج / العرض:</b> " + escapeHtml(order.productName) + "\n" +
                     paymentNote + "\n" +
                     "🔐 <b>بيانات الحساب / الاشتراك:</b>\n" +
                     "<blockquote><code>" + escapeHtml(order.accountData) + "</code></blockquote>\n\n" +
                     "شكراً لتسوقك من <b>Moh Service</b>! 🌟";
        sendHtmlMessage(order.userId, msg, getBackToMainKeyboard());
    }

    public void notifyOrderRejected(DatabaseManager.Order order) {
        if (order == null) return;
        String reason = (order.rejectionReason != null && !order.rejectionReason.isBlank())
                ? escapeHtml(order.rejectionReason)
                : "تعذر التحقق من الطلب";
        String walletNote = "WALLET".equalsIgnoreCase(order.paymentType)
                ? "\n💰 <i>لم يتم خصم أي مبلغ من رصيد محفظتك.</i>\n"
                : "";
        String msg = "❌ <b>تم رفض الطلب رقم #" + order.id + " (" + escapeHtml(order.productName) + ")</b>\n" +
                     walletNote + "\n" +
                     "📌 <b>السبب:</b>\n" +
                     "<blockquote>" + reason + "</blockquote>\n\n" +
                     "💡 <i>في حالة ترى أنه حدث خطأ تواصل مع الدعم الفني.</i>";
        sendHtmlMessage(order.userId, msg, getSupportAndMainKeyboard());
    }

    public int notifyWaitingUsersOfferAvailable(int offerId) {
        DatabaseManager.Offer offer = db.getOfferById(offerId);
        if (offer == null || !offer.isAvailable) return 0;

        List<Long> waitingUsers = db.popWaitingUsersForOffer(offerId);
        if (waitingUsers.isEmpty()) return 0;

        String msg = "🔔 <b>بشرى سارة! العرض الذي تنتظره متوفر الآن!</b>\n\n" +
                     "📦 <b>المنتج:</b> " + escapeHtml(offer.productName) + "\n" +
                     "🏷 <b>العرض:</b> " + escapeHtml(offer.name) + "\n" +
                     "💰 <b>السعر:</b> <code>$" + formatMoney(offer.price) + "</code>\n" +
                     "📌 <b>الحالة:</b> 🟢 متوفر الآن\n\n" +
                     "<blockquote>" + escapeHtml(offer.description != null && !offer.description.isEmpty() ? offer.description : "سارع بالشراء قبل نفاد الكمية!") + "</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton buyBtn = new InlineKeyboardButton("💳 شراء / دفع الآن ($" + formatMoney(offer.price) + ")");
        buyBtn.setCallbackData("offer_" + offer.id);
        row1.add(buyBtn);
        rows.add(row1);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(rows);

        int sentCount = 0;
        for (Long userId : waitingUsers) {
            sendHtmlMessage(userId, msg, markup);
            sentCount++;
        }
        return sentCount;
    }

    // ==================== CALLBACK HANDLERS ====================

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        var maybeMsg = callbackQuery.getMessage();
        long chatId = maybeMsg.getChatId();
        int messageId = maybeMsg.getMessageId();
        boolean isPhotoMessage = (maybeMsg instanceof Message m) && m.hasPhoto();
        String username = callbackQuery.getFrom().getUserName();
        String firstName = callbackQuery.getFrom().getFirstName();

        db.getOrCreateUser(chatId, username, firstName);

        switch (data) {
            case "main_menu" -> {
                clearUserSession(chatId);
                answerCallback(callbackQuery.getId(), null, false);
                editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, buildStartText(firstName), getMainMenuKeyboard());
            }
            case "cancel_payment" -> {
                clearUserSession(chatId);
                answerCallback(callbackQuery.getId(), "تم إلغاء العملية", false);
                editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, "❌ <b>تم إلغاء العملية بناءً على طلبك.</b>", getBackToMainKeyboard());
            }
            case "products" -> {
                clearUserSession(chatId);
                answerCallback(callbackQuery.getId(), null, false);
                showCategoriesMenu(chatId, messageId, isPhotoMessage);
            }
            case "my_account" -> {
                answerCallback(callbackQuery.getId(), null, false);
                showMyAccount(chatId, messageId, isPhotoMessage, username, firstName);
            }
            case "my_orders" -> {
                answerCallback(callbackQuery.getId(), null, false);
                showMyOrders(chatId, messageId, isPhotoMessage);
            }
            case "wallet" -> {
                clearUserSession(chatId);
                answerCallback(callbackQuery.getId(), null, false);
                showWallet(chatId, messageId, isPhotoMessage);
            }
            case "topup_start" -> {
                clearUserSession(chatId);
                answerCallback(callbackQuery.getId(), null, false);
                showTopupCurrencyChoice(chatId, messageId, isPhotoMessage);
            }
            case "topup_curr_USD" -> {
                answerCallback(callbackQuery.getId(), null, false);
                promptForTopupAmount(chatId, messageId, isPhotoMessage, "USD");
            }
            case "topup_curr_DZD" -> {
                answerCallback(callbackQuery.getId(), null, false);
                promptForTopupAmount(chatId, messageId, isPhotoMessage, "DZD");
            }
            case "support" -> {
                answerCallback(callbackQuery.getId(), null, false);
                showSupport(chatId, messageId, isPhotoMessage);
            }
            default -> {
                if (data.startsWith("cat_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    showCategoryProducts(chatId, messageId, isPhotoMessage, data.substring(4));
                } else if (data.startsWith("prod_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    showProductDetailsWithOffers(chatId, messageId, isPhotoMessage, Integer.parseInt(data.substring(5)));
                } else if (data.startsWith("offer_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    showOfferDetails(chatId, messageId, isPhotoMessage, Integer.parseInt(data.substring(6)));
                } else if (data.startsWith("pay_wallet_")) {
                    int offerId = Integer.parseInt(data.substring(11));
                    processWalletOfferPurchase(callbackQuery, chatId, messageId, isPhotoMessage, username, firstName, offerId);
                } else if (data.startsWith("pay_direct_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    int offerId = Integer.parseInt(data.substring(11));
                    showDirectPaymentCurrencyChoice(chatId, messageId, isPhotoMessage, offerId);
                } else if (data.startsWith("dir_curr_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    // Format: dir_curr_USD_12
                    String rest = data.substring(9);
                    String[] p = rest.split("_", 2);
                    showDirectPaymentMethodsForOffer(chatId, messageId, isPhotoMessage, p[0], Integer.parseInt(p[1]));
                } else if (data.startsWith("dir_pm_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    // Format: dir_pm_<methodId>_<offerId>
                    String[] p = data.substring(7).split("_", 2);
                    startDirectOrderPayment(chatId, messageId, isPhotoMessage, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                } else if (data.startsWith("topup_pm_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    // Format: topup_pm_<methodId>_<amount>
                    String[] p = data.substring(9).split("_", 2);
                    startTopupWithMethod(chatId, messageId, isPhotoMessage, Integer.parseInt(p[0]), Double.parseDouble(p[1]));
                } else if (data.startsWith("notify_")) {
                    int offerId = Integer.parseInt(data.substring(7));
                    db.addAvailabilityNotification(chatId, offerId);
                    answerCallback(callbackQuery.getId(), "🔔 تم تسجيل طلبك! سنرسل لك إشعاراً فورياً بمجرد توفر هذا العرض.", true);
                } else {
                    answerCallback(callbackQuery.getId(), null, false);
                }
            }
        }
    }

    // ==================== VIEWS & MENUS ====================

    private String buildStartText(String firstName) {
        String safeName = escapeHtml(firstName != null ? firstName : "عزيزي العميل");
        return "👋 <b>أهلاً بك يا " + safeName + " في بوت Moh Service!</b> 🌟\n\n" +
               "متجرك المتكامل لشراء المنتجات والاشتراكات الرقمية بأفضل الأسعار وبتسليم فوري وآلي.\n\n" +
               "<blockquote>" +
               "🛒 <b>المنتجات:</b> استعرض الفئات والباقات المتوفرة واشترِ بضغطة زر.\n" +
               "👤 <b>حسابي:</b> عرض معلومات حسابك، المعرف الخاص بك، ورصيدك.\n" +
               "📦 <b>طلباتي:</b> سجل مشترياتك السابقة وحالة طلباتك الحالية.\n" +
               "💳 <b>المحفظة:</b> شحن رصيدك بالدولار أو الدينار الجزائري بسهولة.\n" +
               "🛠 <b>الدعم:</b> التواصل المباشر مع الإدارة للاستفسارات والمساعدة." +
               "</blockquote>\n\n" +
               "👇 <b>اختر القسم المطلوب من الأزرار أدناه:</b>";
    }

    private InlineKeyboardMarkup getMainMenuKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton btnProducts = new InlineKeyboardButton("🛒 المنتجات");
        btnProducts.setCallbackData("products");
        row1.add(btnProducts);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnAccount = new InlineKeyboardButton("👤 حسابي");
        btnAccount.setCallbackData("my_account");
        InlineKeyboardButton btnOrders = new InlineKeyboardButton("📦 طلباتي");
        btnOrders.setCallbackData("my_orders");
        row2.add(btnAccount);
        row2.add(btnOrders);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton btnWallet = new InlineKeyboardButton("💳 المحفظة");
        btnWallet.setCallbackData("wallet");
        InlineKeyboardButton btnSupport = new InlineKeyboardButton("🛠 الدعم");
        btnSupport.setCallbackData("support");
        row3.add(btnWallet);
        row3.add(btnSupport);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup getBackToMainKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 الرجوع للقائمة الرئيسية");
        backBtn.setCallbackData("main_menu");
        row.add(backBtn);
        rows.add(row);
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup getSupportAndMainKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r1 = new ArrayList<>();
        InlineKeyboardButton supBtn = new InlineKeyboardButton("🛠 التواصل مع الدعم الفني");
        supBtn.setUrl("tg://user?id=" + ADMIN_CHAT_ID);
        r1.add(supBtn);

        List<InlineKeyboardButton> r2 = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 الرجوع للقائمة الرئيسية");
        backBtn.setCallbackData("main_menu");
        r2.add(backBtn);

        rows.add(r1);
        rows.add(r2);
        return new InlineKeyboardMarkup(rows);
    }

    private void sendStartMessage(long chatId, String firstName) {
        sendHtmlMessage(chatId, buildStartText(firstName), getMainMenuKeyboard());
    }

    private void showCategoriesMenu(long chatId, int messageId, boolean isPhotoMessage) {
        String text = "📂 <b>أقسام وفئات المنتجات:</b>\n\n" +
                      "<blockquote>اختر الفئة المطلوبة لاستعراض المنتجات والعروض المتوفرة بداخلها:</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> r1 = new ArrayList<>();
        InlineKeyboardButton b1 = new InlineKeyboardButton("🎬 اشتراكات الترفيه");
        b1.setCallbackData("cat_1");
        r1.add(b1);

        List<InlineKeyboardButton> r2 = new ArrayList<>();
        InlineKeyboardButton b2 = new InlineKeyboardButton("🤖 اشتراكات الذكاء الاصطناعي");
        b2.setCallbackData("cat_2");
        r2.add(b2);

        List<InlineKeyboardButton> r3 = new ArrayList<>();
        InlineKeyboardButton b3 = new InlineKeyboardButton("📱 اشتراكات السوشيال ميديا");
        b3.setCallbackData("cat_3");
        r3.add(b3);

        List<InlineKeyboardButton> backRow = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 رجوع للقائمة الرئيسية");
        backBtn.setCallbackData("main_menu");
        backRow.add(backBtn);

        rows.add(r1);
        rows.add(r2);
        rows.add(r3);
        rows.add(backRow);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void showCategoryProducts(long chatId, int messageId, boolean isPhotoMessage, String catCode) {
        List<DatabaseManager.Product> products = db.getProductsByCategory(catCode);
        String catTitle = getCategoryName(catCode);

        StringBuilder sb = new StringBuilder();
        sb.append("🛒 <b>").append(escapeHtml(catTitle)).append("</b>\n\n");

        List<List<RichButton>> rows = new ArrayList<>();

        if (products.isEmpty()) {
            sb.append("<blockquote>لا توجد منتجات مضافة في هذه الفئة حالياً. يرجى المحاولة لاحقاً!</blockquote>");
        } else {
            sb.append("<blockquote>اختر المنتج لعرض الباقات والعروض المتوفرة:</blockquote>");

            List<RichButton> currentRow = new ArrayList<>();
            for (DatabaseManager.Product p : products) {
                String emojiId = (p.customEmojiId != null && !p.customEmojiId.isBlank()) ? p.customEmojiId.trim() : null;
                currentRow.add(new RichButton(p.name, "prod_" + p.id, emojiId, null));

                if (currentRow.size() == 3) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
            }
            if (!currentRow.isEmpty()) {
                rows.add(currentRow);
            }
        }

        List<RichButton> backRow = new ArrayList<>();
        backRow.add(new RichButton("🔙 رجوع للفئات", "products", null, null));
        rows.add(backRow);

        editOrReplaceWithRichButtons(chatId, messageId, isPhotoMessage, sb.toString(), rows);
    }

    private void showProductDetailsWithOffers(long chatId, int messageId, boolean isPhotoMessage, int productId) {
        DatabaseManager.Product product = db.getProductById(productId);
        if (product == null) {
            showCategoriesMenu(chatId, messageId, isPhotoMessage);
            return;
        }

        String emojiTag = (product.customEmojiId != null && !product.customEmojiId.isBlank())
                ? "<tg-emoji emoji-id=\"" + escapeHtml(product.customEmojiId.trim()) + "\">⚡</tg-emoji> "
                : "";

        StringBuilder caption = new StringBuilder();
        caption.append(emojiTag).append("<b>").append(escapeHtml(product.name)).append("</b>\n")
               .append("📂 <b>الفئة:</b> ").append(escapeHtml(getCategoryName(product.category))).append("\n\n");

        List<List<RichButton>> rows = new ArrayList<>();

        if (product.offers.isEmpty()) {
            caption.append("<blockquote>لا توجد عروض مضافة لهذا المنتج حالياً.</blockquote>");
        } else {
            caption.append("<blockquote>اختر أحد العروض أدناه لعرض التفاصيل والشراء:\n")
                   .append("🟢 = متوفر للتسليم\n")
                   .append("🔴 = غير متوفر حالياً</blockquote>");

            for (DatabaseManager.Offer offer : product.offers) {
                String statusEmoji = offer.isAvailable ? "🟢" : "🔴";
                String btnStyle = offer.isAvailable ? "success" : "danger";
                String btnText = statusEmoji + " " + offer.name + " - $" + formatMoney(offer.price);
                String prodEmojiId = (product.customEmojiId != null && !product.customEmojiId.isBlank()) ? product.customEmojiId.trim() : null;

                List<RichButton> row = new ArrayList<>();
                row.add(new RichButton(btnText, "offer_" + offer.id, prodEmojiId, btnStyle));
                rows.add(row);
            }
        }

        List<RichButton> backRow = new ArrayList<>();
        backRow.add(new RichButton("🔙 رجوع للمنتجات", "cat_" + product.category, null, null));
        rows.add(backRow);

        editOrReplaceWithRichButtons(chatId, messageId, isPhotoMessage, caption.toString(), rows);
    }

    private void showOfferDetails(long chatId, int messageId, boolean isPhotoMessage, int offerId) {
        DatabaseManager.Offer offer = db.getOfferById(offerId);
        if (offer == null) {
            showCategoriesMenu(chatId, messageId, isPhotoMessage);
            return;
        }

        String availabilityBadge = offer.isAvailable
                ? "🟢 <b>متوفر</b>"
                : "🔴 <b>غير متوفر حالياً</b>";

        String desc = (offer.description != null && !offer.description.isBlank())
                ? escapeHtml(offer.description)
                : "اشتراك رقمي مضمون من Moh Service.";

        double priceDzd = Math.round(offer.price * DatabaseManager.DZD_EXCHANGE_RATE);

        String text = "🏷 <b>تفاصيل العرض:</b>\n\n" +
                      "📦 <b>المنتج:</b> " + escapeHtml(offer.productName) + "\n" +
                      "✨ <b>اسم العرض:</b> " + escapeHtml(offer.name) + "\n" +
                      "💰 <b>السعر بالدولار:</b> <code>$" + formatMoney(offer.price) + " USD</code>\n" +
                      "🇩🇿 <b>السعر بالدينار:</b> <code>" + formatMoney(priceDzd) + " DZD</code>\n" +
                      "📡 <b>حالة التوفر:</b> " + availabilityBadge + "\n\n" +
                      "📝 <b>وصف العرض:</b>\n" +
                      "<blockquote>" + desc + "</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (offer.isAvailable) {
            List<InlineKeyboardButton> rWallet = new ArrayList<>();
            InlineKeyboardButton walletBtn = new InlineKeyboardButton("💰 شراء برصيد المحفظة ($" + formatMoney(offer.price) + ")");
            walletBtn.setCallbackData("pay_wallet_" + offer.id);
            rWallet.add(walletBtn);

            List<InlineKeyboardButton> rDirect = new ArrayList<>();
            InlineKeyboardButton directBtn = new InlineKeyboardButton("💳 دفع مباشر (USD / DZD)");
            directBtn.setCallbackData("pay_direct_" + offer.id);
            rDirect.add(directBtn);

            rows.add(rWallet);
            rows.add(rDirect);
        } else {
            List<InlineKeyboardButton> rNotify = new ArrayList<>();
            InlineKeyboardButton notifyBtn = new InlineKeyboardButton("🔔 أعلمني وقت توفره");
            notifyBtn.setCallbackData("notify_" + offer.id);
            rNotify.add(notifyBtn);
            rows.add(rNotify);
        }

        List<InlineKeyboardButton> backRow = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 رجوع لعروض المنتج");
        backBtn.setCallbackData("prod_" + offer.productId);
        backRow.add(backBtn);
        rows.add(backRow);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void processWalletOfferPurchase(CallbackQuery callbackQuery, long chatId, int messageId, boolean isPhotoMessage, String username, String firstName, int offerId) {
        DatabaseManager.PurchaseResult result = db.purchaseOfferWithWallet(chatId, offerId);

        switch (result.status) {
            case OFFER_NOT_FOUND -> answerCallback(callbackQuery.getId(), "❌ هذا العرض غير موجود!", true);
            case OFFER_UNAVAILABLE -> {
                answerCallback(callbackQuery.getId(), "🔴 عذراً، هذا العرض غير متوفر حالياً!", true);
                showOfferDetails(chatId, messageId, isPhotoMessage, offerId);
            }
            case INSUFFICIENT_BALANCE -> {
                answerCallback(callbackQuery.getId(),
                        "⚠️ عذراً، رصيد محفظتك غير كافٍ!\n" +
                        "سعر العرض: $" + formatMoney(result.offerPrice) + "\n" +
                        "رصيدك الحالي: $" + formatMoney(result.userBalance) + "\n\n" +
                        "يمكنك شحن محفظتك أو استخدام خيار (💳 دفع مباشر).", true);
            }
            case DELIVERED -> {
                answerCallback(callbackQuery.getId(), "✅ تمت عملية الشراء بنجاح!", false);
                DatabaseManager.Order order = result.order;
                String msg = "✅ <b>تم شراء العرض بنجاح! (تسليم فوري)</b>\n\n" +
                             "🧾 <b>رقم الطلب:</b> <code>#" + order.id + "</code>\n" +
                             "📦 <b>العرض:</b> " + escapeHtml(order.productName) + "\n" +
                             "💵 <b>السعر المخصوم:</b> <code>$" + formatMoney(order.price) + "</code>\n" +
                             "💰 <b>رصيدك المتبقي:</b> <code>$" + formatMoney(result.userBalance) + "</code>\n\n" +
                             "🔐 <b>بيانات الحساب / الكود:</b>\n" +
                             "<blockquote><code>" + escapeHtml(order.accountData) + "</code></blockquote>\n\n" +
                             "شكراً لتسوقك من <b>Moh Service</b>! 🌟";
                editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, msg, getBackToMainKeyboard());
            }
            case PENDING -> {
                answerCallback(callbackQuery.getId(), "⏳ تم استلام طلبك وجارٍ التجهيز!", false);
                DatabaseManager.Order order = result.order;
                String clientMsg = "⏳ <b>تم استلام طلبك بنجاح!</b>\n\n" +
                                   "🧾 <b>رقم الطلب:</b> <code>#" + order.id + "</code>\n" +
                                   "📦 <b>العرض:</b> " + escapeHtml(order.productName) + "\n" +
                                   "💳 <b>طريقة الدفع:</b> رصيد المحفظة (<code>$" + formatMoney(order.price) + "</code>)\n\n" +
                                   "<blockquote>لقد استلمنا طلبك، سنقوم بإرساله لك في أقرب وقت ممكن، نشكرك على صبرك ❤️</blockquote>";
                editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, clientMsg, getBackToMainKeyboard());
            }
            default -> answerCallback(callbackQuery.getId(), "❌ حدث خطأ غير متوقع، حاول مرة أخرى.", true);
        }
    }

    private void showMyAccount(long chatId, int messageId, boolean isPhotoMessage, String username, String firstName) {
        DatabaseManager.User user = db.getOrCreateUser(chatId, username, firstName);
        int ordersCount = db.getUserOrdersCount(chatId);

        String text = "👤 <b>معلومات حسابي:</b>\n\n" +
                      "<blockquote>" +
                      "🏷 <b>الاسم:</b> " + escapeHtml(user.firstName) + "\n" +
                      "🔹 <b>المعرف:</b> " + (user.username != null && !user.username.isEmpty() ? "@" + escapeHtml(user.username) : "غير متوفر") + "\n" +
                      "🆔 <b>الآيدي (ID):</b> <code>" + user.chatId + "</code>\n" +
                      "💰 <b>الرصيد الحالي:</b> <code>$" + formatMoney(user.balance) + "</code>\n" +
                      "📦 <b>إجمالي الطلبات:</b> <code>" + ordersCount + "</code>\n" +
                      "📅 <b>تاريخ الانضمام:</b> <code>" + user.createdAt + "</code>" +
                      "</blockquote>";

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, getBackToMainKeyboard());
    }

    private void showMyOrders(long chatId, int messageId, boolean isPhotoMessage) {
        List<DatabaseManager.Order> orders = db.getUserOrders(chatId, 10);
        StringBuilder sb = new StringBuilder();
        sb.append("📦 <b>سجل طلباتي (آخر 10 طلبات):</b>\n\n");

        if (orders.isEmpty()) {
            sb.append("<blockquote>لا يوجد لديك أي طلبات سابقة حتى الآن.</blockquote>");
        } else {
            for (DatabaseManager.Order o : orders) {
                String statusIcon = switch (o.status) {
                    case "COMPLETED" -> "✅ مكتمل";
                    case "REJECTED" -> "❌ مرفوض";
                    case "EXPIRED" -> "⏰ منتهي المهلة";
                    default -> "⏳ قيد التجهيز";
                };
                sb.append("<blockquote>")
                  .append("🧾 <b>طلب رقم:</b> <code>#").append(o.id).append("</code>\n")
                  .append("📦 <b>العرض:</b> ").append(escapeHtml(o.productName)).append("\n")
                  .append("💵 <b>السعر:</b> <code>$").append(formatMoney(o.price)).append("</code>\n")
                  .append("📌 <b>الحالة:</b> ").append(statusIcon).append("\n");
                if (o.accountData != null && !o.accountData.isEmpty()) {
                    sb.append("🔐 <b>البيانات:</b> <code>").append(escapeHtml(o.accountData)).append("</code>\n");
                }
                sb.append("🕒 <b>التاريخ:</b> <code>").append(o.createdAt).append("</code>")
                  .append("</blockquote>\n");
            }
        }

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, sb.toString(), getBackToMainKeyboard());
    }

    private void showWallet(long chatId, int messageId, boolean isPhotoMessage) {
        DatabaseManager.User user = db.getOrCreateUser(chatId, null, null);
        String text = "💳 <b>المحفظة وشحن الرصيد:</b>\n\n" +
                      "🆔 <b>الآيدي الخاص بك:</b> <code>" + chatId + "</code>\n" +
                      "💰 <b>رصيدك الحالي:</b> <code>$" + formatMoney(user.balance) + " USD</code>\n\n" +
                      "<blockquote>" +
                      "يمكنك شحن محفظتك مباشرة عبر بوابات الدفع المتوفرة بالدولار (USD) أو الدينار الجزائري (DZD) بالضغط على زر الشحن أدناه:" +
                      "</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r1 = new ArrayList<>();
        InlineKeyboardButton topupBtn = new InlineKeyboardButton("➕ شحن المحفظة الآن");
        topupBtn.setCallbackData("topup_start");
        r1.add(topupBtn);

        List<InlineKeyboardButton> r2 = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 الرجوع للقائمة الرئيسية");
        backBtn.setCallbackData("main_menu");
        r2.add(backBtn);

        rows.add(r1);
        rows.add(r2);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void showTopupCurrencyChoice(long chatId, int messageId, boolean isPhotoMessage) {
        String text = "💵 <b>شحن المحفظة - اختر عملة الدفع:</b>\n\n" +
                      "<blockquote>اختر العملة التي ترغب بالشحن بها (الدولار الأمريكي USD أو الدينار الجزائري DZD):</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r1 = new ArrayList<>();
        InlineKeyboardButton usdBtn = new InlineKeyboardButton("💵 الدولار الأمريكي (USD)");
        usdBtn.setCallbackData("topup_curr_USD");
        InlineKeyboardButton dzdBtn = new InlineKeyboardButton("🇩🇿 الدينار الجزائري (DZD)");
        dzdBtn.setCallbackData("topup_curr_DZD");
        r1.add(usdBtn);
        r1.add(dzdBtn);

        List<InlineKeyboardButton> r2 = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 رجوع للمحفظة");
        backBtn.setCallbackData("wallet");
        r2.add(backBtn);

        rows.add(r1);
        rows.add(r2);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void promptForTopupAmount(long chatId, int messageId, boolean isPhotoMessage, String currency) {
        clearUserSession(chatId);
        userSessions.put(chatId, new UserPaymentSession(SessionType.ENTERING_TOPUP_AMOUNT, currency, 0, null));

        String currName = "DZD".equalsIgnoreCase(currency) ? "بالدينار الجزائري (DZD)" : "بالدولار الأمريكي (USD)";
        String note = "DZD".equalsIgnoreCase(currency)
                ? "\n<i>ملاحظة: سعر الصرف المعتمد هو 1$ = 260 د.ج (مثلاً 2600 د.ج تشحن لك 10$ في المحفظة).</i>"
                : "";

        String text = "✍️ <b>أدخل المبلغ الذي تريد شحنه " + currName + ":</b>\n" +
                      note + "\n\n" +
                      "<blockquote>أرسل المبلغ الآن كرقم في رسالة (مثال: <code>10</code> أو <code>2600</code>):</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 إلغاء والرجوع");
        backBtn.setCallbackData("topup_start");
        r.add(backBtn);
        rows.add(r);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    private void showSupport(long chatId, int messageId, boolean isPhotoMessage) {
        String text = "🛠 <b>الدعم الفني والمساعدة:</b>\n\n" +
                      "<blockquote>" +
                      "إذا واجهتك أي مشكلة في الطلبات أو عمليات الدفع أو الاستفسار عن المنتجات، تواصل مع الإدارة مباشرة.\n\n" +
                      "📌 يرجى تزويد الدعم بآيدي حسابك: <code>" + chatId + "</code>" +
                      "</blockquote>";

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, getSupportAndMainKeyboard());
    }

    // ==================== HELPER METHODS ====================

    private void sendMessageWithRichButtons(long chatId, String text, List<List<RichButton>> rows) {
        Map<String, Object> replyMarkup = Map.of("inline_keyboard", toRawGrid(rows));
        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML",
                "reply_markup", replyMarkup
        );
        emojiService.postJson("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage", payload);
    }

    private void editOrReplaceWithRichButtons(long chatId, int messageId, boolean isPhotoMessage, String text, List<List<RichButton>> rows) {
        Map<String, Object> replyMarkup = Map.of("inline_keyboard", toRawGrid(rows));

        if (isPhotoMessage) {
            deleteMessageSafe(chatId, messageId);
            Map<String, Object> payload = Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML",
                    "reply_markup", replyMarkup
            );
            emojiService.postJson("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage", payload);
        } else {
            Map<String, Object> payload = Map.of(
                    "chat_id", chatId,
                    "message_id", messageId,
                    "text", text,
                    "parse_mode", "HTML",
                    "reply_markup", replyMarkup
            );
            emojiService.postJson("https://api.telegram.org/bot" + BOT_TOKEN + "/editMessageText", payload);
        }
    }

    private List<List<Map<String, Object>>> toRawGrid(List<List<RichButton>> rows) {
        List<List<Map<String, Object>>> rawGrid = new ArrayList<>();
        for (List<RichButton> r : rows) {
            List<Map<String, Object>> rowList = new ArrayList<>();
            for (RichButton b : r) {
                Map<String, Object> btnMap = new HashMap<>();
                btnMap.put("text", b.text);
                if (b.callbackData != null) btnMap.put("callback_data", b.callbackData);
                if (b.url != null) btnMap.put("url", b.url);
                if (b.iconCustomEmojiId != null && !b.iconCustomEmojiId.isBlank()) {
                    btnMap.put("icon_custom_emoji_id", b.iconCustomEmojiId.trim());
                }
                if (b.style != null && !b.style.isBlank()) {
                    btnMap.put("style", b.style);
                }
                rowList.add(btnMap);
            }
            rawGrid.add(rowList);
        }
        return rawGrid;
    }

    private void editOrReplaceWithHtml(long chatId, int messageId, boolean isPhotoMessage, String text, InlineKeyboardMarkup keyboard) {
        if (isPhotoMessage) {
            deleteMessageSafe(chatId, messageId);
            sendHtmlMessage(chatId, text, keyboard);
        } else {
            editHtmlMessage(chatId, messageId, text, keyboard);
        }
    }

    private void deleteMessageSafe(long chatId, int messageId) {
        DeleteMessage del = DeleteMessage.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .build();
        try {
            execute(del);
        } catch (TelegramApiException ignored) {
        }
    }

    private void sendHtmlMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            System.err.println("Error sending message to " + chatId + ": " + e.getMessage());
        }
    }

    private void editHtmlMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(text)
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard)
                .build();
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            System.err.println("Error editing message: " + e.getMessage());
        }
    }

    private void answerCallback(String callbackId, String text, boolean showAlert) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text(text)
                .showAlert(showAlert)
                .build();
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            System.err.println("Error answering callback: " + e.getMessage());
        }
    }


    private String formatMoney(double amount) {
        if (amount == (long) amount) {
            return String.format("%d", (long) amount);
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }
}
