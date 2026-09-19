package com.careerai.backend.answer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded in-memory caches; neither answers nor source documents are cached. */
@Component
public record AnswerCacheProperties(
        @Value("${careerai.answers.cache.analysis-size:1000}") int analysisSize,
        @Value("${careerai.answers.cache.analysis-ttl-seconds:600}") long analysisTtlSeconds,
        @Value("${careerai.answers.cache.embedding-size:500}") int embeddingSize,
        @Value("${careerai.answers.cache.embedding-ttl-seconds:1800}") long embeddingTtlSeconds
) {
    public AnswerCacheProperties {
        if (analysisSize < 0 || analysisSize > 10000 || embeddingSize < 0 || embeddingSize > 10000
                || analysisTtlSeconds < 1 || analysisTtlSeconds > 86400
                || embeddingTtlSeconds < 1 || embeddingTtlSeconds > 86400) {
            throw new IllegalArgumentException("Answer cache size must be 0..10000 and TTL 1..86400 seconds");
        }
    }
}
