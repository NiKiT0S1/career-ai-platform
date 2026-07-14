package com.careerai.backend.semantic;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Хранит настройки семантического поиска.
 *
 * Feature flag позволяет полностью отключить Semantic RAG
 * и использовать прошлую стабильную логику.
 */

@Getter
@Component
public class SemanticSearchProperties {

    @Value("${semantic-search.enabled:false}")
    private boolean enabled;

    @Value("${semantic-search.embedding-model}")
    private String embeddingModel;

    @Value("${semantic-search.output-dimensions}")
    private int outputDimensions;

    @Value("${semantic-search.index-faq-on-startup:false}")
    private boolean indexFaqOnStartup;

    @Value("${semantic-search.faq-max-results:4}")
    private int faqMaxResults;

    @Value("${semantic-search.faq-min-similarity:0.45}")
    private double faqMinSimilarity;
}
