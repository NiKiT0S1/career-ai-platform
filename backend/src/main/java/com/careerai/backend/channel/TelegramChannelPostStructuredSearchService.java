package com.careerai.backend.channel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Ищет релевантные Telegram-посты через структурированную metadata.
 *
 * Backend перестаёт отдавать LLM просто последние посты подряд
 * и начинает выбирать посты по intent, topic, postType и technologies.
 */

@Service
public class TelegramChannelPostStructuredSearchService {

    private static final int DEFAULT_LIMIT = 15;

    private final TelegramChannelPostMetadataRepository metadataRepository;

    public TelegramChannelPostStructuredSearchService(TelegramChannelPostMetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    /**
     * Возвращает посты, которые подходят под результат анализа пользовательского запроса.
     *
     * @param analysis результат LLM-router'а
     * @return список исходных Telegram-постов, выбранных через metadata
     */
    public List<TelegramChannelPost> findRelevantPosts(ChannelQueryAnalysis analysis) {
        return findRelevantPosts(analysis, DEFAULT_LIMIT);
    }

    /**
     * Возвращает посты, которые подходят под результат анализа пользовательского запроса.
     *
     * @param analysis результат LLM-router'а
     * @param limit    максимальное количество постов
     * @return список исходных Telegram-постов, выбранных через metadata
     */
    public List<TelegramChannelPost> findRelevantPosts(ChannelQueryAnalysis analysis, int limit) {
        if (analysis == null) {
            return List.of();
        }

        return switch (analysis.intent()) {
            case VACANCY -> findVacancyPosts(analysis, limit);
            case PRACTICE -> findPracticePosts(limit);
            case DEADLINE -> findDeadlinePosts(limit);
            case GENERAL_UPDATES -> findGeneralUpdatePosts(limit);
            case FAQ, GENERAL_CHAT, UNKNOWN -> List.of();
        };
    }

    private List<TelegramChannelPost> findVacancyPosts(ChannelQueryAnalysis analysis, int limit) {
        String keyword = normalizeKeyword(analysis.topic());

        if (keyword != null) {
            List<TelegramChannelPost> posts = toPosts(
                    metadataRepository.findLatestSuccessfulVacanciesByKeyword(
                            keyword,
                            PageRequest.of(0, limit)
                    )
            );

            if (!posts.isEmpty()) {
                return posts;
            }
        }

        return toPosts(
                metadataRepository.findLatestSuccessfulVacancies(
                        PageRequest.of(0, limit)
                )
        );
    }

    private List<TelegramChannelPost> findPracticePosts(int limit) {
        return toPosts(
                metadataRepository.findLatestSuccessfulPracticeRelated(
                        PageRequest.of(0, limit)
                )
        );
    }

    private List<TelegramChannelPost> findDeadlinePosts(int limit) {
        return findByPostTypes(
                List.of(
                        TelegramChannelPostType.DEADLINE,
                        TelegramChannelPostType.PRACTICE,
                        TelegramChannelPostType.EVENT,
                        TelegramChannelPostType.VACANCY
                ),
                limit
        );
    }

    private List<TelegramChannelPost> findGeneralUpdatePosts(int limit) {
        return findByPostTypes(
                List.of(
                        TelegramChannelPostType.ANNOUNCEMENT,
                        TelegramChannelPostType.EVENT,
                        TelegramChannelPostType.PRACTICE,
                        TelegramChannelPostType.DEADLINE,
                        TelegramChannelPostType.VACANCY
                ),
                limit
        );
    }

    private List<TelegramChannelPost> findByPostTypes(Collection<TelegramChannelPostType> postTypes, int limit) {
        return toPosts(
                metadataRepository.findLatestSuccessfulByPostTypes(
                        postTypes,
                        PageRequest.of(0, limit)
                )
        );
    }

    private List<TelegramChannelPost> toPosts(List<TelegramChannelPostMetadata> metadataList) {
        return metadataList.stream()
                .map(TelegramChannelPostMetadata::getPost)
                .filter(Objects::nonNull)
                .toList();
    }

    private String normalizeKeyword(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }

        String normalizedTopic = topic.trim().toLowerCase(Locale.ROOT);

        return switch (normalizedTopic) {
            case "java", "джав", "джава", "джавист", "java_developer" -> "java";
            case "python", "питон", "питонист", "python_developer" -> "python";
            case "frontend", "front", "фронт", "фронтенд", "frontend_developer" -> "frontend";
            case "backend", "back", "бэк", "бек", "бэкенд", "backend_developer" -> "backend";
            case "ml", "machine learning", "машинное обучение", "ml_developer" -> "ml";
            default -> normalizedTopic;
        };
    }
}
