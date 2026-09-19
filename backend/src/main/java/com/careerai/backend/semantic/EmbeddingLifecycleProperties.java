package com.careerai.backend.semantic;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class EmbeddingLifecycleProperties {
    @Value("${semantic-search.lifecycle.enabled:true}")
    private boolean enabled;
    @Value("${semantic-search.lifecycle.batch-size:10}")
    private int batchSize;
    @Value("${semantic-search.lifecycle.retry-initial-seconds:60}")
    private long retryInitialSeconds;
    @Value("${semantic-search.lifecycle.retry-max-seconds:3600}")
    private long retryMaxSeconds;
}
