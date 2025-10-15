# Repository Guidelines

## Project Structure & Module Organization
- `pom.xml` defines the Maven build, Java 21 toolchain, and third-party dependencies (Netty, Agrona, Jackson, SLF4J/Log4j).
- Runtime code lives under `src/main/java/com/akakata`, grouped by responsibility (`context`, `server`, `communication`, `handlers`, `protocols`, `service`, `app/impl`). Follow these packages when adding new components.
- Configuration and shared assets sit in `src/main/resources`, with network/server settings in `props/conf.properties` and logging in `log4j.properties`.
- Reserve `src/test/java` for unit and integration tests that mirror the production package structure.

## Build, Test, and Development Commands
- `mvn clean package` performs a fresh compile and produces `target/Zea-tcp-1.0-SNAPSHOT.jar`.
- `mvn test` runs the test suite; add JUnit Jupiter dependencies if your tests require them.
- `java -cp target/Zea-tcp-1.0-SNAPSHOT.jar com.akakata.Main` boots the TCP/HTTP/WebSocket servers after a build. Provide a matching shutdown hook when adding new services.

## Coding Style & Naming Conventions
- Use Java 21 features judiciously; keep indentation at 4 spaces and braces on the same line as declarations.
- Package names stay under `com.akakata.<module>`; favour descriptive class names (`DefaultPlayerSession`, `ServerContext`).
- Obtain loggers via `LoggerFactory.getLogger` and prefer structured messages over string concatenation.
- Keep configuration keys lowercase with dot separators (see `props/conf.properties`), and document new keys inline.

## Testing Guidelines
- Co-locate tests with their source package and suffix classes with `Test` (for example, `ServerContextTest`).
- Mock network boundaries aggressively; fast unit tests allow `mvn test` to stay in CI.
- Aim for coverage on session lifecycle, protocol handlers, and error paths. Use dedicated integration tests for Netty pipelines when behaviour depends on channel states.

## Commit & Pull Request Guidelines
- Current history uses short, numbered summaries; continue writing a brief, present-tense subject (≤50 chars) followed by optional context in the body.
- Reference relevant issues in the body (`Refs #123`) and note any configuration changes.
- Pull requests should explain the motivation, outline functional impacts, attach logs or screenshots for protocol changes, and list manual test steps when automation is not yet in place.

## Configuration & Security Tips
- Never commit secrets; load credentials via environment variables that `conf.properties` can reference.
- Validate new ports and bind addresses against the shutdown hook in `Main` to guarantee graceful teardown.
