package com.careerai.backend.semantic;

/**
 * Абстракция над AI-провайдером, создающим embedding-векторы.
 */

public interface EmbeddingProvider {

    EmbeddingResult embedQuery(String text); // Создаёт embedding для вопроса пользователя

    EmbeddingResult embedDocument(String title, String text); // Создаёт embedding для документа из базы знаний
}
