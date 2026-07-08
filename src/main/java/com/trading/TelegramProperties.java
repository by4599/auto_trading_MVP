package com.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application-paper.yml의 telegram.* 값을 바인딩한다.
 *
 * bot-token이 비어있으면 TelegramNotifier가 알림을 스킵한다 (개발 환경 graceful degradation).
 */
@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {

    private String botToken = "";
    private String chatId   = "";

    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) { this.botToken = botToken; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
}
