# AI Chat App Server

Ktor-based backend for an AI character chat application. The service provides authentication, character management, chats, messages, reviews, feedback, recommendations, and image upload support.

## Tech Stack

- Kotlin JVM 17
- Ktor
- KMongo / MongoDB
- kotlinx.serialization
- AWS S3 SDK
- Gradle

## Requirements

- JDK 17+
- MongoDB
- API credentials for at least one supported model provider if AI responses are enabled
- AWS S3 credentials if image uploads are enabled

## Configuration

Runtime configuration is provided through environment variables. Use `.env.example` as a template and keep real secrets out of git.

Key variables:

- `MONGODB_URI` - MongoDB connection string
- `USE_GROQ`, `GROQ_API_URL`, `GROQ_API_KEY`, `GROQ_MODEL`
- `USE_OPEN_AI`, `OPEN_AI_API_URL`, `OPEN_AI_API_KEY`, `OPEN_AI_MODEL`
- `USE_GEMINI`, `GEMINI_API_URL`, `GEMINI_API_KEY`, `GEMINI_MODEL`
- `AI_TEMPERATURE`, `AI_PROMPT`, `AI_GROUP_CHAT_PROMPT`
- `GOOGLE_OAUTH_TOKEN_INFO_URL`
- `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `AWS_S3_BUCKET_NAME`, `AWS_REGION`

## Run Locally

```bash
./gradlew run
```

The server starts on port `8080`.

## Build

```bash
./gradlew build
```

## Repository Hygiene

Do not commit local `.env` files, IDE metadata, build output, logs, API keys, service account files, or private certificates. The Gradle wrapper JAR is intentionally allowed in git.
