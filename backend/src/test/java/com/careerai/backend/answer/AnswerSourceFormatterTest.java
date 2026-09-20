package com.careerai.backend.answer;

import com.careerai.backend.channel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnswerSourceFormatterTest {
    @ParameterizedTest
    @CsvSource({"Какие вакансии?,не подтверждает", "What jobs are available?,does not confirm", "Қандай вакансиялар бар?,растамайды"})
    void unconfirmedOffersAreExplicitlyQualifiedInUserLanguage(String question, String qualification) {
        var post = new TelegramChannelPost(); post.setId(1L); post.setText("Java Intern");
        post.setChannelUsername("career_channel"); post.setTelegramMessageId(17L);
        var result = new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(ChannelContentScope.VACANCIES, List.of(post))));
        String answer = AnswerSourceFormatter.append(question, "Java Intern", result);
        assertTrue(answer.contains(qualification), answer);
        assertTrue(answer.contains("https://t.me/career_channel/17"));
    }
    @Test void duplicatesAreNotAddedAndMissingUrlsAreNotInvented() {
        var first = new TelegramChannelPost(); first.setId(1L); first.setChannelUsername("career_channel"); first.setTelegramMessageId(17L);
        var missing = new TelegramChannelPost(); missing.setId(2L);
        var result = new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(ChannelContentScope.PRACTICE, List.of(first, first, missing))));
        String existing = "Срок указан здесь: https://t.me/career_channel/17";
        assertEquals(existing, AnswerSourceFormatter.append("Какой срок?", existing, result));
    }

    @ParameterizedTest
    @CsvSource({"Құжаттарды қашан тапсыру керек?,мерзімі өткен", "What is the deadline?,deadline has passed", "Какой срок?,срок истёк"})
    void localizesGeneratedInternalLabelBeforeAddingSources(String question,String expectedStatus) {
        var first=new TelegramChannelPost();first.setId(1L);first.setChannelUsername("career_channel");first.setTelegramMessageId(17L);
        var result=new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(ChannelContentScope.PRACTICE,List.of(first))));
        String answer=AnswerSourceFormatter.append(question,"<b>AITU CareerAI</b>: 7 сентября (срок истёк)",result);
        assertTrue(answer.contains("("+expectedStatus+")"),answer);
        assertTrue(answer.contains("<b>AITU CareerAI</b>"),answer);
        assertTrue(answer.contains("https://t.me/career_channel/17"),answer);
    }
}
