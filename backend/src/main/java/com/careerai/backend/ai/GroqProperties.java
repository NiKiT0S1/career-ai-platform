package com.careerai.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Хранит настройки для обращения к Groq API.
 *
 * Groq используется как быстрый экспериментальный LLM-провайдер. Настройки позволяют
 * выбрать URL API, модель, API-ключ, таймауты и базовые параметры генерации ответа.
 */

@ConfigurationProperties(prefix = "groq.api")
public class GroqProperties {

    private String baseUrl = "https://api.groq.com/openai/v1";
    private String model = "llama-3.1-8b-instant";
    private String key = "";

    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 25;

    private double temperature = 0.5;
    private int maxOutputTokens = 1100;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
