# AI Chat App Server

Backend server for an AI character chat application. It handles user accounts, authentication, character profiles, chats, messages, reviews, feedback, recommendations, and image uploads.

The project is built around a Kotlin/Ktor API with MongoDB persistence. It supports AI message generation through external model providers and includes background jobs for recommendation and activity updates.

## Main Features

- User authentication with guest and Google-based flows
- Character creation, editing, search, tags, categories, and visibility settings
- One-on-one and group chat support
- Message streaming and chat synchronization
- Reviews, likes, reports, feedback, and follow relationships
- Recommendation caches and character ranking updates
- Image upload and storage integration

## Built With

- Kotlin
- Ktor
- MongoDB with KMongo
- kotlinx.serialization
- Kotlin coroutines
- AWS S3 SDK
- Gradle
