package com.mohservice;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProductPriceFetcherService {

    public static final double SERVICE_COMMISSION_USD = 0.50;

    public static class FetchResult {
        public boolean validPlatform;
        public String platform; // AliExpress, Temu, SHEIN
        public boolean priceFound;
        public double originalPriceUsd;
        public String productTitle;

        public FetchResult(boolean validPlatform, String platform, boolean priceFound, double originalPriceUsd, String productTitle) {
            this.validPlatform = validPlatform;
            this.platform = platform;
            this.priceFound = priceFound;
            this.originalPriceUsd = originalPriceUsd;
            this.productTitle = productTitle != null ? productTitle : "";
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String detectPlatform(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("aliexpress.") || lower.contains("a.aliexpress.")) return "AliExpress";
        if (lower.contains("temu.")) return "Temu";
        if (lower.contains("shein.")) return "SHEIN";
        return null;
    }

    public FetchResult fetchProductPrice(String rawUrl) {
        String url = extractFirstUrl(rawUrl);
        if (url == null) {
            return new FetchResult(false, null, false, 0, "");
        }
        String platform = detectPlatform(url);
        if (platform == null) {
            return new FetchResult(false, null, false, 0, "");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                String html = response.body();
                if (html != null && !html.isEmpty()) {
                    String title = extractTitle(html);
                    Double price = extractPriceFromHtml(html);
                    if (price != null && price > 0 && price < 100000) {
                        return new FetchResult(true, platform, true, Math.round(price * 100.0) / 100.0, title);
                    }
                    return new FetchResult(true, platform, false, 0, title);
                }
            }
        } catch (Exception e) {
            System.err.println("Price fetch error for " + url + ": " + e.getMessage());
        }
        return new FetchResult(true, platform, false, 0, "");
    }

    public String extractFirstUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(https?://[^\\s]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String extractTitle(String html) {
        Matcher og = Pattern.compile("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (og.find()) {
            return cleanTitle(og.group(1));
        }
        Matcher t = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (t.find()) {
            return cleanTitle(t.group(1));
        }
        return "";
    }

    private String cleanTitle(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 90) cleaned = cleaned.substring(0, 87) + "...";
        return cleaned;
    }

    private Double extractPriceFromHtml(String html) {
        String[] patterns = new String[]{
                "<meta[^>]+property=[\"']product:price:amount[\"'][^>]+content=[\"'](\\d+(?:\\.\\d+)?)[\"']",
                "\"lowPrice\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)\"?",
                "\"price\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)\"?",
                "\"minAmount\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*(\\d+(?:\\.\\d+)?)",
                "\"actMinPrice\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)\"?",
                "\"salePrice\"\\s*:\\s*\\{[^}]*\"amount\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)\"?",
                "\"retailPrice\"\\s*:\\s*\\{[^}]*\"amount\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)\"?",
                "\\$\\s*(\\d+\\.\\d{2})"
        };
        for (String p : patterns) {
            Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) {
                try {
                    double val = Double.parseDouble(m.group(1));
                    if (val > 0) return val;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }
}
