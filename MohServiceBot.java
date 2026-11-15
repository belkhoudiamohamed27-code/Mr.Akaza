package com.mohservice;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MohServiceBot extends TelegramLongPollingBot {

    public static final String BOT_TOKEN = "9058888272:AAFsCFbiZJWKvu328D1_wY4rEtQebAJAfrY";
    public static final String BOT_USERNAME = "MohServiceBot";
    public static final long ADMIN_CHAT_ID = 8780415382L;

    private final DatabaseManager db;
    private final TelegramEmojiService emojiService;

    public MohServiceBot(DatabaseManager db, TelegramEmojiService emojiService) {
        super(BOT_TOKEN);
        this.db = db;
        this.emojiService = emojiService;
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
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleIncomingMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            System.err.println("Error processing update: " + e.getMessage());
        }
    }

    private void handleIncomingMessage(Message message) {
        long chatId = message.getChatId();
        String text = message.getText().trim();
        String username = message.getFrom().getUserName();
        String firstName = message.getFrom().getFirstName();

        db.getOrCreateUser(chatId, username, firstName);

        if (text.equals("/start") || text.startsWith("/start ")) {
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
                    "<code>/addprod الاسم | السعر</code>\n\n" +
                    "مثال:\n" +
                    "<code>/addprod نتفليكس شهر | 5.0</code>", null);
            return;
        }

        String name = parts[0].trim();
        String priceStr = parts[1].trim();
        if (name.isEmpty()) {
            sendHtmlMessage(chatId, "⚠️ <b>يرجى كتابة اسم المنتج.</b>", null);
            return;
        }

        double price;
        try {
            price = Double.parseDouble(priceStr);
            if (price < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>السعر غير صالح! يرجى إدخال رقم صحيح أو عشري.</b>", null);
            return;
        }

        int productId = db.addProduct(name, price);
        if (productId != -1) {
            sendHtmlMessage(chatId,
                    "✅ <b>تم إضافة المنتج والعرض الافتراضي بنجاح!</b>\n\n" +
                    "🆔 <b>رقم المنتج:</b> <code>" + productId + "</code>\n" +
                    "📦 <b>الاسم:</b> " + escapeHtml(name) + "\n" +
                    "💰 <b>السعر:</b> <code>$" + formatMoney(price) + "</code>", null);
        } else {
            sendHtmlMessage(chatId, "❌ <b>حدث خطأ أثناء إضافة المنتج إلى قاعدة البيانات.</b>", null);
        }
    }

    private void handleAddStock(long chatId, String text) {
        String payload = text.replaceFirst("^/addstock\\s*", "").trim();
        String[] parts = payload.split("\\|", 2);
        if (parts.length < 2) {
            sendHtmlMessage(chatId,
                    "⚠️ <b>صيغة الأمر غير صحيحة!</b>\n\n" +
                    "الاستخدام الصحيح:\n" +
                    "<code>/addstock رقم_المنتج | بيانات_الحساب</code>\n\n" +
                    "مثال:\n" +
                    "<code>/addstock 1 | email@test.com:pass123</code>", null);
            return;
        }

        int productId;
        try {
            productId = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>رقم المنتج غير صالح!</b>", null);
            return;
        }

        String accountData = parts[1].trim();
        if (accountData.isEmpty()) {
            sendHtmlMessage(chatId, "⚠️ <b>يرجى إدخال بيانات الحساب.</b>", null);
            return;
        }

        DatabaseManager.Product product = db.getProductById(productId);
        if (product == null) {
            sendHtmlMessage(chatId, "❌ <b>لم يتم العثور على منتج برقم:</b> <code>" + productId + "</code>", null);
            return;
        }

        boolean added = db.addStock(productId, accountData);
        if (added) {
            sendHtmlMessage(chatId,
                    "✅ <b>تمت إضافة المخزون بنجاح!</b>\n\n" +
                    "📦 <b>المنتج:</b> " + escapeHtml(product.name) + " (#" + productId + ")\n" +
                    "🔐 <b>البيانات المضافة:</b> <code>" + escapeHtml(accountData) + "</code>", null);
        } else {
            sendHtmlMessage(chatId, "❌ <b>حدث خطأ أثناء إضافة المخزون.</b>", null);
        }
    }

    private void handleAddBal(long chatId, String text) {
        String payload = text.replaceFirst("^/addbal\\s*", "").trim();
        String[] parts = payload.split("\\s+");
        if (parts.length < 2) {
            sendHtmlMessage(chatId,
                    "⚠️ <b>صيغة الأمر غير صحيحة!</b>\n\n" +
                    "الاستخدام الصحيح:\n" +
                    "<code>/addbal آيدي_العميل المبلغ</code>\n\n" +
                    "مثال:\n" +
                    "<code>/addbal 123456789 15.0</code>", null);
            return;
        }

        long targetUserId;
        double amount;
        try {
            targetUserId = Long.parseLong(parts[0].trim());
            amount = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            sendHtmlMessage(chatId, "⚠️ <b>تأكد من صحة آيدي العميل والمبلغ!</b>", null);
            return;
        }

        double newBalance = db.addBalance(targetUserId, amount);
        if (newBalance >= 0) {
            sendHtmlMessage(chatId,
                    "✅ <b>تم شحن رصيد العميل بنجاح!</b>\n\n" +
                    "👤 <b>آيدي العميل:</b> <code>" + targetUserId + "</code>\n" +
                    "💵 <b>المبلغ المضاف:</b> <code>$" + formatMoney(amount) + "</code>\n" +
                    "💰 <b>الرصيد الجديد:</b> <code>$" + formatMoney(newBalance) + "</code>", null);

            notifyUserBalanceUpdated(targetUserId, amount, newBalance, true);
        } else {
            sendHtmlMessage(chatId, "❌ <b>حدث خطأ أثناء تحديث رصيد العميل.</b>", null);
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

    public void notifyOrderManuallyDelivered(DatabaseManager.Order order) {
        if (order == null) return;
        String msg = "✅ <b>تم تجهيز وتسليم طلبك!</b>\n\n" +
                     "🧾 <b>رقم الطلب:</b> <code>#" + order.id + "</code>\n" +
                     "📦 <b>المنتج / العرض:</b> " + escapeHtml(order.productName) + "\n" +
                     "💵 <b>السعر المدفوع:</b> <code>$" + formatMoney(order.price) + "</code>\n\n" +
                     "🔐 <b>بيانات الحساب / الاشتراك:</b>\n" +
                     "<blockquote><code>" + escapeHtml(order.accountData) + "</code></blockquote>\n\n" +
                     "شكراً لتسوقك من <b>Moh Service</b>! 🌟";
        sendHtmlMessage(order.userId, msg, getBackToMainKeyboard());
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
                answerCallback(callbackQuery.getId(), null, false);
                editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, buildStartText(firstName), getMainMenuKeyboard());
            }
            case "products" -> {
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
                answerCallback(callbackQuery.getId(), null, false);
                showWallet(chatId, messageId, isPhotoMessage);
            }
            case "support" -> {
                answerCallback(callbackQuery.getId(), null, false);
                showSupport(chatId, messageId, isPhotoMessage);
            }
            default -> {
                if (data.startsWith("cat_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    String catCode = data.substring(4);
                    showCategoryProducts(chatId, messageId, isPhotoMessage, catCode);
                } else if (data.startsWith("prod_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    int productId = Integer.parseInt(data.substring(5));
                    showProductDetailsWithOffers(chatId, messageId, isPhotoMessage, productId);
                } else if (data.startsWith("offer_")) {
                    answerCallback(callbackQuery.getId(), null, false);
                    int offerId = Integer.parseInt(data.substring(6));
                    showOfferDetails(chatId, messageId, isPhotoMessage, offerId);
                } else if (data.startsWith("pay_")) {
                    int offerId = Integer.parseInt(data.substring(4));
                    processOfferPurchase(callbackQuery, chatId, messageId, isPhotoMessage, username, firstName, offerId);
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
               "💳 <b>المحفظة:</b> معرفة رصيدك الحالي وطريقة شحن الرصيد.\n" +
               "🛠 <b>الدعم:</b> التواصل المباشر مع الإدارة للاستفسارات والمساعدة." +
               "</blockquote>\n\n" +
               "👇 <b>اختر القسم المطلوب من الأزرار أدناه:</b>";
    }

    private InlineKeyboardMarkup getMainMenuKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Row 1: زر (المنتجات) بعرض كامل
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton btnProducts = new InlineKeyboardButton("🛒 المنتجات");
        btnProducts.setCallbackData("products");
        row1.add(btnProducts);

        // Row 2: صف (حسابي | طلباتي)
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnAccount = new InlineKeyboardButton("👤 حسابي");
        btnAccount.setCallbackData("my_account");
        InlineKeyboardButton btnOrders = new InlineKeyboardButton("📦 طلباتي");
        btnOrders.setCallbackData("my_orders");
        row2.add(btnAccount);
        row2.add(btnOrders);

        // Row 3: صف (المحفظة | الدعم)
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

    private void sendStartMessage(long chatId, String firstName) {
        sendHtmlMessage(chatId, buildStartText(firstName), getMainMenuKeyboard());
    }

    // Step 1: Show the 3 Categories
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

    // Step 2: Show Products inside chosen Category (Grid of up to 3 columns per row with custom platform logo icons!)
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

    // Step 3: Show Product Details + Offers (With Custom Platform Icon + Green/Red styled buttons!)
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

    // Step 4: Show Offer Details (Buy button if available, Notify Me button if unavailable)
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

        String text = "🏷 <b>تفاصيل العرض:</b>\n\n" +
                      "📦 <b>المنتج:</b> " + escapeHtml(offer.productName) + "\n" +
                      "✨ <b>اسم العرض:</b> " + escapeHtml(offer.name) + "\n" +
                      "💰 <b>السعر:</b> <code>$" + formatMoney(offer.price) + "</code>\n" +
                      "📡 <b>حالة التوفر:</b> " + availabilityBadge + "\n\n" +
                      "📝 <b>وصف العرض:</b>\n" +
                      "<blockquote>" + desc + "</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> actionRow = new ArrayList<>();
        if (offer.isAvailable) {
            InlineKeyboardButton buyBtn = new InlineKeyboardButton("💳 شراء / دفع الآن");
            buyBtn.setCallbackData("pay_" + offer.id);
            actionRow.add(buyBtn);
        } else {
            InlineKeyboardButton notifyBtn = new InlineKeyboardButton("🔔 أعلمني وقت توفره");
            notifyBtn.setCallbackData("notify_" + offer.id);
            actionRow.add(notifyBtn);
        }
        rows.add(actionRow);

        List<InlineKeyboardButton> backRow = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 رجوع لعروض المنتج");
        backBtn.setCallbackData("prod_" + offer.productId);
        backRow.add(backBtn);
        rows.add(backRow);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    // Step 5: Process Purchase of Offer
    private void processOfferPurchase(CallbackQuery callbackQuery, long chatId, int messageId, boolean isPhotoMessage, String username, String firstName, int offerId) {
        DatabaseManager.PurchaseResult result = db.purchaseOffer(chatId, offerId);

        switch (result.status) {
            case OFFER_NOT_FOUND -> {
                answerCallback(callbackQuery.getId(), "❌ هذا العرض غير موجود!", true);
            }
            case OFFER_UNAVAILABLE -> {
                answerCallback(callbackQuery.getId(), "🔴 عذراً، هذا العرض غير متوفر حالياً!", true);
                showOfferDetails(chatId, messageId, isPhotoMessage, offerId);
            }
            case INSUFFICIENT_BALANCE -> {
                answerCallback(callbackQuery.getId(),
                        "⚠️ عذراً، رصيدك غير كافٍ لشراء هذا العرض!\n" +
                        "سعر العرض: $" + formatMoney(result.offerPrice) + "\n" +
                        "رصيدك الحالي: $" + formatMoney(result.userBalance), true);
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
                                   "💵 <b>السعر المخصوم:</b> <code>$" + formatMoney(order.price) + "</code>\n" +
                                   "💰 <b>رصيدك المتبقي:</b> <code>$" + formatMoney(result.userBalance) + "</code>\n\n" +
                                   "<blockquote>لقد استلمنا طلبك، سنقوم بإرساله لك في أقرب وقت ممكن، نشكرك على صبرك ❤️</blockquote>";
                editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, clientMsg, getBackToMainKeyboard());

                // Only send the extra admin notification if the buyer is not the admin himself, or format it clearly as an Admin Alert
                if (chatId != ADMIN_CHAT_ID) {
                    String userTag = (username != null && !username.isEmpty()) ? "@" + username : escapeHtml(firstName);
                    String adminAlert = "🚨 <b>إشعار للإدارة: طلب جديد قيد الانتظار (PENDING)!</b>\n\n" +
                                        "🧾 <b>رقم الطلب:</b> <code>#" + order.id + "</code>\n" +
                                        "👤 <b>العميل:</b> " + userTag + "\n" +
                                        "🆔 <b>آيدي العميل:</b> <code>" + chatId + "</code>\n" +
                                        "📦 <b>العرض المطلوب:</b> " + escapeHtml(order.productName) + "\n" +
                                        "💵 <b>السعر المدفوع:</b> <code>$" + formatMoney(order.price) + "</code>\n" +
                                        "🕒 <b>التوقيت:</b> <code>" + order.createdAt + "</code>";
                    sendHtmlMessage(ADMIN_CHAT_ID, adminAlert, null);
                }
            }
            default -> {
                answerCallback(callbackQuery.getId(), "❌ حدث خطأ غير متوقع، حاول مرة أخرى.", true);
            }
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
                String statusIcon = "COMPLETED".equals(o.status) ? "✅ مكتمل" : "⏳ قيد التجهيز (PENDING)";
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
                      "💰 <b>رصيدك الحالي:</b> <code>$" + formatMoney(user.balance) + "</code>\n\n" +
                      "<blockquote>" +
                      "لشحن رصيد محفظتك، يرجى نسخ الآيدي الخاص بك (<code>" + chatId + "</code>) والتواصل مع الإدارة أو الدعم الفني لإتمام عملية الشحن." +
                      "</blockquote>";

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, getBackToMainKeyboard());
    }

    private void showSupport(long chatId, int messageId, boolean isPhotoMessage) {
        String text = "🛠 <b>الدعم الفني والمساعدة:</b>\n\n" +
                      "<blockquote>" +
                      "إذا واجهتك أي مشكلة في الطلبات أو كنت ترغب في شحن رصيدك أو الاستفسار عن المنتجات، تواصل مع الإدارة مباشرة.\n\n" +
                      "📌 يرجى تزويد الدعم بآيدي حسابك: <code>" + chatId + "</code>" +
                      "</blockquote>";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> contactRow = new ArrayList<>();
        InlineKeyboardButton contactBtn = new InlineKeyboardButton("💬 مراسلة الإدارة");
        contactBtn.setUrl("tg://user?id=" + ADMIN_CHAT_ID);
        contactRow.add(contactBtn);
        rows.add(contactRow);

        List<InlineKeyboardButton> backRow = new ArrayList<>();
        InlineKeyboardButton backBtn = new InlineKeyboardButton("🔙 الرجوع للقائمة الرئيسية");
        backBtn.setCallbackData("main_menu");
        backRow.add(backBtn);
        rows.add(backRow);

        editOrReplaceWithHtml(chatId, messageId, isPhotoMessage, text, new InlineKeyboardMarkup(rows));
    }

    // ==================== HELPER METHODS ====================

    private void editOrReplaceWithRichButtons(long chatId, int messageId, boolean isPhotoMessage, String text, List<List<RichButton>> rows) {
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

        Map<String, Object> replyMarkup = Map.of("inline_keyboard", rawGrid);

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
        return String.format("%.2f", amount);
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }
}
