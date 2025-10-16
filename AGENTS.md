# Repository Guidelines

## Project Structure & Module Organization
- Runtime sources live in `src/main/java/com/akakata`. Key areas: `context` (Dagger modules, `ServerContext`, configuration), `server` (Netty bootstrapping), `handlers` (login, HTTP, WebSocket pipelines), `protocols` (SBE/JSON wiring), `event` (Agrona sharded dispatcher), and `app/impl` (game/session domain objects).
- Shared assets are in `src/main/resources`: `props/conf.properties` controls ports, thread counts, and metrics; `logback.xml` defines logging; `sbe/zea-message-schema.xml` stores the Aeron-compatible message schema.
- Add tests under `src/test/java`, matching the production package structure. Create sub-packages (for example `com.akakata.server`) to mirror the code under test.

## Build, Test, and Development Commands
- `mvn clean package` – compiles with annotation processing (Dagger) and emits `target/Zea-tcp-0.7.8.jar`.
- `mvn test` – runs the unit test suite; add JUnit 5 dependencies in the POM when introducing new tests.
- `mvn exec:java -Dexec.mainClass=com.akakata.Main -Dexec.args="--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"` – convenient way to start all transports with the required Agrona JVM flag. Equivalent manual command after packaging: `java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED -cp target/classes:target/dependency/* com.akakata.Main`.

## Coding Style & Naming Conventions
- Project targets Java 17. Use 4 spaces for indentation, braces on the same line, and final fields wherever possible.
- Prefer constructor injection; Dagger will auto-generate factories from `ServiceModule`, `ProtocolModule`, and `ServerModule`. New injectable classes should expose a constructor receiving all dependencies.
- Packages stay under `com.akakata.<module>` and class names describe their role (`ShardedEventDispatcher`, `SbeProtocol`, etc.).
- Obtain loggers through SLF4J (`LoggerFactory.getLogger`) and rely on Logback configuration for formatting.

## Testing Guidelines
- Add JUnit 5 tests that suffix classes with `Test` (for example `ShardedEventDispatcherTest`). Keep Netty and Agrona interactions behind interfaces so they can be mocked.
- Cover session lifecycle, dispatcher back-pressure behaviour, and protocol interoperability. Integration tests that spin up Netty pipelines should live in a dedicated package (for example `com.akakata.it`).

## Commit & Pull Request Guidelines
- Use short, imperative commit subjects (≤50 chars) with optional context in the body, and reference issues (`Refs #123`) where applicable.
- PRs must describe motivation, configuration changes (ports, VM flags), and manual verification steps. Attach logs or screenshots when touching login flows or network protocols.
- Update `AGENTS.md`, `conf.properties`, or `logback.xml` when behaviour changes, and note any required JVM options in the PR description.

## Configuration & Security Tips
- Never commit secrets; when adding new configuration keys keep them lowercase with dot delimiters and document defaults in `conf.properties`.
- Always run servers with `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED`. Validate external ports against firewalls before merging.
