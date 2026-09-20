package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import com.careerai.backend.answer.*;
import com.careerai.backend.faq.FaqEntryService;
import com.careerai.backend.semantic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Optional REAL provider smoke. No Spring context, user database, embeddings, or Telegram calls.
 * Enable explicitly with CAREERAI_LIVE_SMOKE=true and ordinary GEMINI_API_KEYS/GROQ_API_KEY.
 * Run only this class: mvn -Dtest=LiveStudentRegressionSmokeTest test
 * UTF-8 reports under target/live-smoke contain synthetic inputs and answers, never credentials.
 * Mocked retrieval is deliberate: this checks routing/generation, not live retrieval relevance.
 */
@EnabledIfEnvironmentVariable(named = "CAREERAI_LIVE_SMOKE", matches = "(?i)true")
@Execution(ExecutionMode.SAME_THREAD)
class LiveStudentRegressionSmokeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Asia/Almaty"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MultilingualDateTextParser dates = new MultilingualDateTextParser(
            new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector());

    enum Scenario { DOCUMENTS, HISTORICAL_EVENT, UNKNOWN_YEAR, UPCOMING_EVENTS }
    record Case(String id, String question, Scenario scenario) {
        @Override public String toString() { return id; }
    }
    record Call(String task, String provider, String model, boolean success, String error, long elapsedMs, String response) {}

    static Stream<Case> cases() {
        return Stream.of(
                new Case("deadline-ru", "Докумнты на практику до какого числа здать? Я уже опоздал?", Scenario.DOCUMENTS),
                new Case("deadline-kz", "Практика құжаттарын тапсыру мерзімі қашан бітті? Кешігіп калдым ба?", Scenario.DOCUMENTS),
                new Case("deadline-en", "What was the deadlien for internship documents? Am I too late?", Scenario.DOCUMENTS),
                new Case("event-history-ru", "Когда проходил ивент в Open Space? Какая была дата?", Scenario.HISTORICAL_EVENT),
                new Case("event-history-kz", "Open Space іс-шарасы қашан өтті? Күні қандай болды?", Scenario.HISTORICAL_EVENT),
                new Case("unknown-year-ru", "У вакансии дедлайн 1 апреля. Какой это год, я еще успиваю?", Scenario.UNKNOWN_YEAR),
                new Case("unknown-year-en", "The vacancy says April 1. Which year is it, can I still apply?", Scenario.UNKNOWN_YEAR),
                new Case("unknown-year-kz", "Вакансияда 1 сәуір деп жазылған. Қай жыл? Өтінім беруге әлі үлгерем бе?", Scenario.UNKNOWN_YEAR),
                new Case("upcoming-en", "What evnts are happening now or coming up?", Scenario.UPCOMING_EVENTS))
                .filter(scenario -> {
                    String selected = System.getenv("CAREERAI_LIVE_SMOKE_CASES");
                    return selected == null || selected.isBlank()
                            || Arrays.asList(selected.split(",")).contains(scenario.id());
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Timeout(120)
    void realRouterAndGenerationRespectSyntheticEvidence(Case scenario) throws Exception {
        long started = System.nanoTime();
        RecordingProvider provider = new RecordingProvider(configuredProvider());
        ChannelQueryAnalyzer router = new ChannelQueryAnalyzer(provider, JSON,
                new ChannelQueryAnalysisRequestFactory(CLOCK), new AnswerCacheProperties(16, 600, 0, 600), CLOCK);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("case", scenario.id());
        report.put("question", scenario.question());
        report.put("syntheticDataOnly", true);
        report.put("fixedDate", "2026-09-19");
        String status = "FAILED";
        try {
            ChannelQueryAnalysis analysis = router.analyze(scenario.question());
            report.put("analysis", analysis);
            assertNotEquals(ChannelSearchIntent.UNKNOWN, analysis.intent(), "Router did not produce a usable analysis");
            assertTrue(analysis.needsChannelPosts(), "Student facts require channel evidence");
            assertTrue(analysis.hasScope(expectedScope(scenario.scenario()))
                    || analysis.hasScope(ChannelContentScope.ALL_UPDATES)
                    || (scenario.scenario() == Scenario.DOCUMENTS && analysis.hasScope(ChannelContentScope.DEADLINES))
                    || (scenario.scenario() == Scenario.UNKNOWN_YEAR && analysis.hasScope(ChannelContentScope.DEADLINES)),
                    "Wrong scope: " + analysis.contentScopes());
            if (scenario.scenario() == Scenario.DOCUMENTS) assertTrue(analysis.needsDeadlines());
            if (scenario.scenario() == Scenario.HISTORICAL_EVENT) {
                assertNotEquals(ChannelFreshnessScope.CURRENT, analysis.freshnessScope(), "Historical question lost its past context");
            }

            Fixture fixture = fixture(scenario.scenario());
            TelegramChannelPostAnswerService answers = answers(provider, router, fixture);
            String answer = answers.buildAnswerIfRelevant(scenario.question()).orElseThrow();
            report.put("answer", answer);
            assertFalse(answer.isBlank());
            assertTrue(provider.calls.stream().anyMatch(call -> call.task().equals(LlmTaskType.USER_ANSWER.name())),
                    "No real answer-generation request was made");
            // A failed router call may be recovered by the deterministic policy. Keep
            // that failure in the report, but judge the answer by its actual facts.
            assertTrue(provider.calls.stream().filter(call -> call.task().equals(LlmTaskType.USER_ANSWER.name()))
                    .allMatch(Call::success), "Answer provider failed; inspect the report for error types");
            checkFacts(scenario.scenario(), answer);
            assertLanguage(scenario.id(), answer);
            status = "PASSED";
        } catch (AssertionError failure) {
            report.put("failure", failure.getMessage());
            throw failure;
        } finally {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            report.put("status", status);
            report.put("elapsedMs", elapsed);
            report.put("calls", provider.calls);
            Path output = Path.of("target", "live-smoke", scenario.id() + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
            System.out.printf("Live smoke %s: %s, %d ms, %d logical provider calls%n", scenario.id(), status, elapsed, provider.calls.size());
        }
    }

    private TelegramChannelPostAnswerService answers(LlmProvider provider, ChannelQueryAnalyzer router, Fixture fixture) {
        var posts = mock(TelegramChannelPostRepository.class);
        var hybrid = mock(TelegramChannelPostHybridSearchService.class);
        var timeline = mock(ChannelPostTimelineSearchService.class);
        var metadata = mock(TelegramChannelPostMetadataRepository.class);
        when(hybrid.findRelevantPosts(any(), any(), anyInt())).thenReturn(fixture.result());
        when(timeline.search(any(), anyInt(), anyString())).thenReturn(fixture.result());
        when(timeline.searchDeadlineKnowledge(any(), anyInt(), anyString())).thenReturn(fixture.result());
        when(metadata.findByPostId(anyLong())).thenAnswer(call -> Optional.ofNullable(fixture.metadata().get(call.getArgument(0))));
        var temporal = new EventTemporalEvidenceService(metadata, dates, CLOCK);
        return new TelegramChannelPostAnswerService(posts, router, provider, hybrid,
                mock(FaqEntryService.class), mock(FaqSemanticSearchService.class), mock(SemanticQueryEmbeddingService.class),
                new AnswerExecutionPlanner(), mock(StructuredChannelAnswerBuilder.class),
                new TelegramChannelPostSearchEligibility(CLOCK), timeline, CLOCK, temporal,
                new EventContextFilter(temporal, dates, CLOCK), new AnswerCalendarGrounding(dates, CLOCK));
    }

    private void checkFacts(Scenario scenario, String answer) {
        if (answer.startsWith("Publication selection:") || answer.startsWith("Выборка по публикациям:")
                || answer.startsWith("Жарияланымдар іріктемесі:")) {
            answer = answer.substring(answer.indexOf("\n\n") + 2);
        }
        List<MultilingualDateTextParser.DateMention> mentions = dates.calendarMentions(answer.replaceAll("<[^>]+>", ""));
        switch (scenario) {
            case DOCUMENTS -> {
                assertTrue(hasDate(mentions, 7, 9), "The latest document extension (7 September) was not answered");
                assertTrue(answer.toLowerCase(Locale.ROOT).matches("(?s).*(ист[её]к|прош[её]л|опозд|законч|заверш|аяқтал|кешік|өткен|өтіп|өтті|expired|passed|past|too late|missed).*$"),
                        "Expired deadline was not explained");
            }
            case HISTORICAL_EVENT -> {
                assertTrue(hasDate(mentions, 10, 8), "The event occurred on August 10, not its July publication date");
                assertFalse(hasDate(mentions, 10, 7), "Publication date leaked into the event answer");
            }
            case UNKNOWN_YEAR -> {
                assertTrue(hasDate(mentions, 1, 4), "The source date April 1 was lost");
                assertTrue(mentions.stream().filter(date -> date.day() == 1 && date.month() == 4)
                        .allMatch(date -> date.year() == null), "A missing deadline year was invented");
                assertTrue(answer.toLowerCase(Locale.ROOT).matches("(?s).*(не указан|неяс|уточн|не определ|белгісіз|көрсетілмеген|нақтыла|жазылмаған|not specified|unspecified|unclear|not stated|confirm|clarif|not provided|missing).*$"),
                        "Missing-year uncertainty was not explained");
            }
            case UPCOMING_EVENTS -> {
                assertTrue(hasDate(mentions, 15, 11), "The actual upcoming November workshop was lost");
                assertFalse(answer.contains("Open Space"), "A past event reappeared in the upcoming list");
                assertFalse(hasDate(mentions, 10, 7));
                assertFalse(hasDate(mentions, 10, 8));
            }
        }
    }

    private static boolean hasDate(List<MultilingualDateTextParser.DateMention> dates, int day, int month) {
        return dates.stream().anyMatch(date -> date.day() == day && date.month() == month);
    }

    private void assertLanguage(String id, String answer) {
        // This is a coarse smoke signal, not a complete linguistic-quality evaluation.
        if (id.endsWith("-kz")) {
            assertTrue(answer.matches("(?s).*[әіңғүұқөһӘІҢҒҮҰҚӨҺ].*"), "Kazakh response expected");
            assertFalse(answer.toLowerCase(Locale.ROOT).contains("срок истёк"), "Internal Russian status must be translated");
        }
        if (id.endsWith("-ru")) assertTrue(answer.matches("(?s).*[а-яА-ЯёЁ].*"), "Russian response expected");
        String prose = answer;
        for (var mention : dates.calendarMentions(answer)) prose = prose.replace(mention.text(), "");
        if (id.endsWith("-en")) assertFalse(prose.matches("(?s).*[а-яА-ЯёЁәіңғүұқөһ].*"), "English response expected outside source date quotations");
    }

    private Fixture fixture(Scenario scenario) {
        List<TelegramChannelPost> posts = new ArrayList<>();
        List<TelegramChannelPostRelation> relations = new ArrayList<>();
        Map<Long, TelegramChannelPostMetadata> metadata = new HashMap<>();
        if (scenario == Scenario.DOCUMENTS) {
            var original = post(1, "Документы на производственную практику сдать до 25 августа 2026 года включительно.", true);
            var extension = post(2, "Срок сдачи документов на производственную практику продлён до 7 сентября 2026 года включительно.", true);
            posts.addAll(List.of(original, extension));
            relations.add(relation(extension, original, TelegramChannelPostRelationType.UPDATE));
        } else if (scenario == Scenario.UNKNOWN_YEAR) {
            var vacancy = post(3, "Тестовая вакансия Java Intern. Дедлайн подачи заявок — 1 апреля. Компания: Synthetic Career Lab.", false);
            posts.add(vacancy);
            var data = metadata(vacancy, TelegramChannelPostType.VACANCY);
            data.setDeadlineText("1 апреля");
            metadata.put(3L, data);
        } else {
            var event = post(4, "10 августа 2026 года состоится мероприятие в Open Space. Время: с 12:00 до 15:00. Обещана раздача Coca-Cola.", true);
            var cancellation = post(5, "Раздача Coca-Cola на этом мероприятии отменена. Остальная программа сохраняется.", false);
            posts.addAll(List.of(event, cancellation));
            relations.add(relation(cancellation, event, TelegramChannelPostRelationType.CANCELLATION));
            var data = metadata(event, TelegramChannelPostType.EVENT);
            data.setEventDateText("10 августа 2026 года");
            metadata.put(4L, data);
            if (scenario == Scenario.UPCOMING_EVENTS) {
                var future = post(6, "Мастер-класс по составлению резюме состоится 15 ноября 2026 года.", false);
                posts.add(future);
                var futureData = metadata(future, TelegramChannelPostType.EVENT);
                futureData.setEventDateText("15 ноября 2026 года");
                metadata.put(6L, futureData);
            }
        }
        return new Fixture(new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(expectedScope(scenario), posts)), relations), metadata);
    }

    private static TelegramChannelPost post(long id, String text, boolean expired) {
        var post = new TelegramChannelPost();
        post.setId(id);
        post.setTelegramMessageId(id);
        post.setChannelUsername("careerai_synthetic_smoke");
        post.setText(text);
        post.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00").plusMinutes(id));
        post.setFreshnessStatus(expired ? TelegramChannelPostFreshnessStatus.EXPIRED : TelegramChannelPostFreshnessStatus.UNKNOWN);
        return post;
    }
    private static TelegramChannelPostMetadata metadata(TelegramChannelPost post, TelegramChannelPostType type) {
        var metadata = new TelegramChannelPostMetadata();
        metadata.setPost(post); metadata.setPostType(type); metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
        return metadata;
    }
    private static TelegramChannelPostRelation relation(TelegramChannelPost source, TelegramChannelPost target, TelegramChannelPostRelationType type) {
        var relation = new TelegramChannelPostRelation();
        relation.setSourcePost(source); relation.setTargetPost(target); relation.setRelationType(type);
        relation.setReason(type == TelegramChannelPostRelationType.CANCELLATION ? "Отменена только раздача Coca-Cola" : "Продлён срок сдачи документов");
        return relation;
    }
    private static ChannelContentScope expectedScope(Scenario scenario) {
        return switch (scenario) {
            case DOCUMENTS -> ChannelContentScope.PRACTICE;
            case UNKNOWN_YEAR -> ChannelContentScope.VACANCIES;
            case HISTORICAL_EVENT, UPCOMING_EVENTS -> ChannelContentScope.EVENTS;
        };
    }
    record Fixture(ChannelPostSearchResult result, Map<Long, TelegramChannelPostMetadata> metadata) {}

    private static LlmProvider configuredProvider() throws Exception {
        Properties config = new Properties();
        try (var input = LiveStudentRegressionSmokeTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(input, "application.properties unavailable");
            config.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        String selected = env("LLM_PROVIDER", config.getProperty("llm.provider", "GEMINI"));
        var active = new LlmProperties();
        active.setProvider(LlmProviderType.valueOf(selected.toUpperCase(Locale.ROOT)));
        var groq = new GroqProperties();
        groq.setKey(env("GROQ_API_KEY", ""));
        groq.setModel(env("GROQ_API_MODEL", "openai/gpt-oss-20b"));
        groq.setBaseUrl(config.getProperty("groq.api.base-url", "https://api.groq.com/openai/v1"));
        groq.setMaxOutputTokens(Integer.parseInt(config.getProperty("groq.api.max-output-tokens", "1200")));
        String geminiKeys = env("GEMINI_API_KEYS", "");
        GeminiLlmProvider gemini;
        if (!geminiKeys.isBlank()) {
            var properties = new GeminiProperties();
            ReflectionTestUtils.setField(properties, "apiKeys", geminiKeys);
            ReflectionTestUtils.setField(properties, "model", env("GEMINI_API_MODEL", config.getProperty("gemini.api.model")));
            ReflectionTestUtils.setField(properties, "baseUrl", config.getProperty("gemini.api.base-url"));
            gemini = new GeminiLlmProvider(properties, JSON, new GeminiKeyPool(properties));
        } else {
            assertEquals(LlmProviderType.GROQ, active.getProvider(), "GEMINI_API_KEYS is required for the ordinary GEMINI configuration");
            gemini = mock(GeminiLlmProvider.class);
            when(gemini.execute(any())).thenReturn(LlmResponse.failure("Gemini not configured for this GROQ-only smoke", "Gemini", "none", LlmErrorType.NO_AVAILABLE_KEYS, 0));
        }
        assertTrue(!groq.getKey().isBlank() || !geminiKeys.isBlank(), "Configure a provider key in environment variables");
        return new ActiveLlmProvider(active, gemini, new GroqLlmProvider(groq, JSON));
    }
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static final class RecordingProvider implements LlmProvider {
        private final LlmProvider delegate;
        private final List<Call> calls = new ArrayList<>();
        private RecordingProvider(LlmProvider delegate) { this.delegate = delegate; }
        @Override public LlmResponse execute(LlmRequest request) {
            LlmResponse response = delegate.execute(request);
            calls.add(new Call(request.taskType().name(), response.provider(), response.model(), response.success(),
                    response.errorType().name(), response.elapsedMillis(), response.text()));
            return response;
        }
    }
}
