package com.careerai.backend.channel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

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
    public List<TelegramChannelPost> findRelevantPosts(
            ChannelQueryAnalysis analysis,
            int limit
    ) {
        if (analysis == null
                || !analysis.needsChannelPosts()) {
            return List.of();
        }

        List<TelegramChannelPost> result =
                new ArrayList<>();

        Set<Long> addedIds =
                new LinkedHashSet<>();

        for (ChannelContentScope scope
                : analysis.contentScopes()) {

            List<TelegramChannelPost> scopePosts =
                    findRelevantPostsForScope(
                            analysis,
                            scope,
                            limit
                    );

            for (TelegramChannelPost post : scopePosts) {
                if (result.size() >= limit) {
                    return List.copyOf(result);
                }

                if (post != null
                        && post.getId() != null
                        && addedIds.add(post.getId())) {
                    result.add(post);
                }
            }
        }

        return List.copyOf(result);
    }

    public List<TelegramChannelPost> findRelevantPostsForScope(
            ChannelQueryAnalysis analysis,
            ChannelContentScope scope,
            int limit
    ) {
        if (analysis == null
                || scope == null
                || !analysis.needsChannelPosts()) {
            return List.of();
        }

        return switch (scope) {
            case VACANCIES ->
                    findVacancyPosts(analysis, limit);

            case EVENTS ->
                    findEventPosts(limit);

            case PRACTICE ->
                    findPracticePosts(limit);

            case DEADLINES ->
                    findDeadlinePosts(limit);

            case ALL_UPDATES ->
                    findGeneralUpdatePosts(limit);

            case NONE ->
                    List.of();
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

    private List<TelegramChannelPost> findEventPosts(int limit) {
        return findByPostTypes(
                List.of(
                        TelegramChannelPostType.EVENT,
                        TelegramChannelPostType.ANNOUNCEMENT
                ),
                limit
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

    private List<TelegramChannelPost> findByIntentFallback(ChannelQueryAnalysis analysis, int limit) {
        return switch (analysis.intent()) {
            case VACANCY -> findVacancyPosts(analysis, limit);
            case PRACTICE -> findPracticePosts(limit);
            case DEADLINE -> findDeadlinePosts(limit);
            case GENERAL_UPDATES ->  findGeneralUpdatePosts(limit);
            case FAQ, GENERAL_CHAT,UNKNOWN -> List.of();
        };
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
