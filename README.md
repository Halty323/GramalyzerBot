# GramalyzerBot

The shared Java 21 / Spring Boot backend for the Telegram bot and Gramalyzer admin website. The supplied `src.zip` has been extracted into a buildable Maven project. Existing user registration and bot commands remain; the `workspace` package adds the admin API, channel configuration, observed subscriber history, text-post queue, and Telegram delivery worker.

Run the demo together with the UI from the sibling GramalyzerVibe checkout:

```sh
cd ../GramalyzerVibe
npm run dev
npm test
```

Or, with a local JDK 21 and Maven:

```sh
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

The demo profile never starts Telegram polling or sends posts. It uses a separate persistent H2 database under `.data/demo`. Non-demo operation requires `GRAMALYZER_API_TOKEN`; live polling additionally requires a rotated `TELEGRAM_BOT_TOKEN` and `TELEGRAM_BOT_ENABLED=true`. Configure the existing PostgreSQL connection using Spring datasource environment variables. See the sibling GramalyzerVibe README for endpoints, deployment, tests, and limitations.

Maven explicitly declares Lombok as an annotation processor, using Spring Boot's managed Lombok version. This is required on JDK 23 and newer; without it, compilation reports missing constructors, getters/setters, and uninitialized final fields. After updating an older checkout, run `mvn clean test` before restarting. Use `mvn -v` to check the JDK Maven actually runs with. Clean builds are verified on JDK 21 and 25.

**The original source archive contains a hardcoded bot token. Revoke it through BotFather.** Extracted configuration now uses environment variables without credential defaults. The old token remains in the committed archive and history; do not reuse it.

The two original persistence defects that prevented reliable registration have been fixed: new users now receive `createdAt` before insertion, and the repository helper delegates to the actual JPA repository instead of returning stub repositories. Non-message Telegram updates no longer enter message-only handlers.

Live delivery is not validated until a replacement token and your target channel/database are configured. Individual moderator permissions, historical post-view analytics, media publishing, delivery notifications, and service-message cleanup are not implemented in this first shared-backend version.
