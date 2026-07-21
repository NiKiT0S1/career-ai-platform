package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelLlmRequestFactoryTest {

    @Test
    void createsFastGroqFirstQueryAnalysisRequest() {
        ChannelQueryAnalysisRequestFactory factory = new ChannelQueryAnalysisRequestFactory();

        LlmRequest request = factory.create("Какие сейчас есть вакансии?");

        assertEquals(LlmTaskType.QUERY_ANALYSIS, request.taskType());
        assertEquals(LlmResponseFormat.JSON, request.responseFormat());
        assertEquals(LlmTimeoutProfile.FAST, request.timeoutProfile());
        assertEquals(LlmProviderStrategy.GROQ_FIRST, request.providerStrategy());
        assertEquals(0.0, request.temperature());
    }

    @Test
    void createsSeparateMetadataExtractionRequest() {
        TelegramChannelPostMetadataRequestFactory factory =
                new TelegramChannelPostMetadataRequestFactory();

        LlmRequest request = factory.create("Открыта новая вакансия Java Intern");

        assertEquals(LlmTaskType.CHANNEL_METADATA_EXTRACTION, request.taskType());
        assertEquals(LlmResponseFormat.JSON, request.responseFormat());
        assertEquals(LlmTimeoutProfile.STANDARD, request.timeoutProfile());
        assertEquals(LlmProviderStrategy.ACTIVE_WITH_FALLBACK, request.providerStrategy());
    }
}