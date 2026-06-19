# CareerAI Platform

CareerAI is a Telegram-based AI assistant for the AITU Career Center.

At the current stage, the project contains a Spring Boot backend that connects a Telegram bot with Google Gemini API. A student can send a message to the Telegram bot, the backend receives it, sends the text to Gemini, and returns the AI-generated answer back to Telegram.

## Repository Structure

```text
career-ai-platform/
├── backend/
├── scripts/
├── .gitignore
└── README.md
```

## Backend Structure

```text
backend/
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── careerai/
│   │   │           └── backend/
│   │   │               ├── BackendApplication.java
│   │   │               ├── ai/
│   │   │               │   ├── GeminiLlmProvider.java
│   │   │               │   ├── GeminiProperties.java
│   │   │               │   └── LlmProvider.java
│   │   │               ├── health/
│   │   │               │   └── HealthCheckController.java
│   │   │               └── telegram/
│   │   │                   ├── TelegramBotProperties.java
│   │   │                   ├── TelegramBotService.java
│   │   │                   ├── TelegramPollingService.java
│   │   │                   └── TelegramSendMessageRequest.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/
│           └── com/
│               └── careerai/
│                   └── backend/
│                       └── BackendApplicationTests.java
├── .gitattributes
├── .gitignore
├── mvnw
├── mvnw.cmd
└── pom.xml
```

## Tech Stack

* Java 21
* Spring Boot 3
* Maven
* Telegram Bot API
* Google Gemini API
* Lombok
* Spring Boot Actuator

## Current Features

* Spring Boot backend application
* Health check endpoint
* Telegram bot integration through polling
* `/start` command handling
* Receiving text messages from Telegram
* Sending user messages to Google Gemini API
* Returning Gemini responses back to Telegram
* Basic fallback response if Gemini API is temporarily unavailable

## How It Works

```text
Student sends a message to Telegram bot
        ↓
Spring Boot backend receives the message through polling
        ↓
Backend extracts chat ID and message text
        ↓
Backend sends the text to Gemini API
        ↓
Gemini generates an answer
        ↓
Backend sends the answer back to Telegram
```

## Environment Variables

Before running the backend, configure the following environment variables:

```text
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
GEMINI_API_KEY=your_gemini_api_key
```

Do not store real tokens or API keys directly in the source code.

## Backend Configuration

The backend configuration is located here:

```text
backend/src/main/resources/application.properties
```

Current configuration example:

```properties
spring.application.name=careerai-backend
server.port=8080

management.endpoints.web.exposure.include=health,info

telegram.bot.token=${TELEGRAM_BOT_TOKEN}

gemini.api.key=${GEMINI_API_KEY}
gemini.api.model=gemini-2.5-flash
gemini.api.base-url=https://generativelanguage.googleapis.com/v1beta/models
```

## Run Locally

Open the `backend/` directory in IntelliJ IDEA and run:

```text
BackendApplication
```

Or run from terminal:

```bash
cd backend
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

## Health Check

After starting the backend, open:

```text
http://localhost:8080/actuator/health
```

Expected result:

```json
{
  "status": "UP"
}
```

A custom health endpoint is also available:

```text
http://localhost:8080/api/health
```

## Telegram Bot

The Telegram bot currently works through polling.

This means that the backend regularly asks Telegram for new messages. For local development, this is simpler than using webhooks because the backend runs on `localhost`.

Current bot behavior:

* `/start` returns a local greeting message
* any other text message is sent to Gemini API
* Gemini response is sent back to the same Telegram chat
* if Gemini API is unavailable, the bot returns a fallback message

## Project Status

The project is currently in the first MVP stage.

The main goal of this stage is to make the basic integration work:

```text
Telegram Bot → Spring Boot Backend → Gemini API → Telegram Bot
```
