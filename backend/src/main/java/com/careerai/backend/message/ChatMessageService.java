package com.careerai.backend.message;

import com.careerai.backend.user.TelegramUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Сервис для работы с историей сообщений.
 *
 * Он сохраняет входящие сообщения студентов и ответы CareerAI в базу данных.
 * Сейчас это нужно для фиксации истории общения, а в будущем может стать основой
 * для памяти диалога и анализа частых вопросов.
 */

@Service
public class ChatMessageService {

    private ChatMessageRepository chatMessageRepository;

    public ChatMessageService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    @Transactional
    public void saveUserMessage(TelegramUser telegramUser,
                                long chatId,
                                String text,
                                Long telegramMessageId) {
        saveMessage(telegramUser, chatId, MessageRole.USER, text, telegramMessageId);
    }

    @Transactional
    public void saveAssistantMessage(TelegramUser telegramUser,
                                     long chatId,
                                     String text) {
        saveMessage(telegramUser, chatId, MessageRole.ASSISTANT, text, null);
    }

    private void saveMessage(TelegramUser telegramUser,
                             long chatId,
                             MessageRole role,
                             String text,
                             Long telegramMessageId) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setTelegramUser(telegramUser);
        chatMessage.setTelegramChatId(chatId);
        chatMessage.setRole(role);
        chatMessage.setText(text);
        chatMessage.setTelegramMessageId(telegramMessageId);
        chatMessage.setCreatedAt(LocalDateTime.now());

        chatMessageRepository.save(chatMessage);
    }
}
