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

    @Value("${semantic-search.index-channel-posts-on-startup:false}")
    private boolean indexChannelPostsOnStartup;

    @Value("${semantic-search.channel-index-limit:100}")
    private int channelIndexLimit;

    @Value("${semantic-search.channel-max-results:6}")
    private int channelMaxResults;

    @Value("${semantic-search.channel-min-similarity:0.45}")
    private double channelMinSimilarity;
}
