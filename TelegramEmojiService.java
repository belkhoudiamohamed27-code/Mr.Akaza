package com.mohservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

@Service
public class TelegramEmojiService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String cachedBotUsername = null;

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + MohServiceBot.BOT_TOKEN + "/" + method;
    }

    public String getRealBotUsername() {
        if (cachedBotUsername != null) return cachedBotUsername;
        try {
            String resp = restTemplate.getForObject(apiUrl("getMe"), String.class);
            JsonNode root = objectMapper.readTree(resp);
            if (root.path("ok").asBoolean()) {
                cachedBotUsername = root.path("result").path("username").asText();
                return cachedBotUsername;
            }
        } catch (Exception e) {
            System.err.println("Error getting bot username: " + e.getMessage());
        }
        return MohServiceBot.BOT_USERNAME;
    }

    /**
     * Converts any uploaded image file into a 100x100 PNG, uploads it to Telegram's Custom Emoji Sticker Set
     * owned by ADMIN_CHAT_ID, and returns the generated custom_emoji_id!
     */
    public String createCustomEmojiFromImage(File imageFile) {
        try {
            byte[] png100Bytes = resizeTo100x100Png(imageFile);
            if (png100Bytes == null) return "";

            // 1. Upload sticker file to Telegram
            String uploadedFileId = uploadStickerFile(png100Bytes);
            if (uploadedFileId == null || uploadedFileId.isEmpty()) {
                return "";
            }

            String setName = "moh_icons_by_" + getRealBotUsername();

            // 2. Check if sticker set exists
            boolean setExists = checkStickerSetExists(setName);

            Map<String, Object> stickerObj = Map.of(
                    "sticker", uploadedFileId,
                    "format", "static",
                    "emoji_list", List.of("⚡")
            );

            if (!setExists) {
                Map<String, Object> createBody = Map.of(
                        "user_id", MohServiceBot.ADMIN_CHAT_ID,
                        "name", setName,
                        "title", "Moh Service Icons",
                        "stickers", List.of(stickerObj),
                        "sticker_type", "custom_emoji"
                );
                postJson(apiUrl("createNewStickerSet"), createBody);
            } else {
                Map<String, Object> addBody = Map.of(
                        "user_id", MohServiceBot.ADMIN_CHAT_ID,
                        "name", setName,
                        "sticker", stickerObj
                );
                postJson(apiUrl("addStickerToSet"), addBody);
            }

            // 3. Fetch the sticker set and get the latest sticker's custom_emoji_id
            String setResp = restTemplate.getForObject(apiUrl("getStickerSet?name=" + setName), String.class);
            JsonNode root = objectMapper.readTree(setResp);
            if (root.path("ok").asBoolean()) {
                JsonNode stickers = root.path("result").path("stickers");
                if (stickers.isArray() && !stickers.isEmpty()) {
                    JsonNode lastSticker = stickers.get(stickers.size() - 1);
                    return lastSticker.path("custom_emoji_id").asText("");
                }
            }
        } catch (Exception e) {
            System.err.println("Error creating custom emoji from image: " + e.getMessage());
        }
        return "";
    }

    private byte[] resizeTo100x100Png(File inputFile) {
        try {
            BufferedImage src = ImageIO.read(inputFile);
            if (src == null) return null;

            BufferedImage dst = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = dst.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Preserve aspect ratio and center inside 100x100 canvas
            int sw = src.getWidth();
            int sh = src.getHeight();
            double scale = Math.min(100.0 / sw, 100.0 / sh);
            int nw = Math.max(1, (int) Math.round(sw * scale));
            int nh = Math.max(1, (int) Math.round(sh * scale));
            int x = (100 - nw) / 2;
            int y = (100 - nh) / 2;

            g2d.drawImage(src, x, y, nw, nh, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(dst, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("Error resizing image to 100x100: " + e.getMessage());
            return null;
        }
    }

    private String uploadStickerFile(byte[] pngBytes) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("user_id", String.valueOf(MohServiceBot.ADMIN_CHAT_ID));
            body.add("sticker_format", "static");

            ByteArrayResource fileResource = new ByteArrayResource(pngBytes) {
                @Override
                public String getFilename() {
                    return "icon.png";
                }
            };
            body.add("sticker", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("uploadStickerFile"), requestEntity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.path("ok").asBoolean()) {
                return root.path("result").path("file_id").asText("");
            }
        } catch (Exception e) {
            System.err.println("Error uploading sticker file: " + e.getMessage());
        }
        return "";
    }

    private boolean checkStickerSetExists(String setName) {
        try {
            String resp = restTemplate.getForObject(apiUrl("getStickerSet?name=" + setName), String.class);
            JsonNode root = objectMapper.readTree(resp);
            return root.path("ok").asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    public void postJson(String url, Object payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String json = objectMapper.writeValueAsString(payload);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            restTemplate.postForEntity(url, entity, String.class);
        } catch (Exception e) {
            System.err.println("Telegram JSON API error (" + url + "): " + e.getMessage());
        }
    }

    public String downloadTelegramFileToUploads(String fileId, String prefix) {
        try {
            String resp = restTemplate.getForObject(apiUrl("getFile?file_id=" + fileId), String.class);
            JsonNode root = objectMapper.readTree(resp);
            if (root.path("ok").asBoolean()) {
                String tgFilePath = root.path("result").path("file_path").asText("");
                if (!tgFilePath.isEmpty()) {
                    String ext = ".jpg";
                    if (tgFilePath.contains(".")) {
                        ext = tgFilePath.substring(tgFilePath.lastIndexOf("."));
                    }
                    String downloadUrl = "https://api.telegram.org/file/bot" + MohServiceBot.BOT_TOKEN + "/" + tgFilePath;
                    byte[] fileBytes = restTemplate.getForObject(downloadUrl, byte[].class);
                    if (fileBytes != null && fileBytes.length > 0) {
                        new File("uploads").mkdirs();
                        String localName = prefix + "_" + System.currentTimeMillis() + ext;
                        java.nio.file.Files.write(java.nio.file.Paths.get("uploads", localName), fileBytes);
                        return "uploads/" + localName;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error downloading Telegram file: " + e.getMessage());
        }
        return "";
    }
}
