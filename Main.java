package com.mohservice;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    public CommandLineRunner startTelegramBot(MohServiceBot bot) {
        return args -> {
            System.out.println("Starting Moh Service Telegram Bot & Web Admin Panel...");
            try {
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
                botsApi.registerBot(bot);
                System.out.println("✅ Moh Service Telegram Bot is running!");
                System.out.println("🌐 Web Admin Panel is available at: http://localhost:8080 (Password: admin)");
            } catch (TelegramApiException e) {
                System.err.println("Failed to register Telegram bot: " + e.getMessage());
                e.printStackTrace();
            }
        };
    }
}
