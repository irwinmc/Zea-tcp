# Repository Guidelines

## Project Structure & Module Organization
Runtime sources live under `src/main/java/com/akakata`, with key domains in `context` for Dagger wiring, `server` for Netty bootstrapping, `handlers` for login and transport pipelines, `protocols` for SBE and JSON translation, and `event` for the Agrona dispatcher. Session and gameplay objects reside in `app/impl`. Shared assets stay in `src/main/resources`, including `props/conf.properties`, `logback.xml`, and `sbe/zea-message-schema.xml`. Mirror production packages in `src/test/java` when adding tests (for example `src/test/java/com/akakata/server`).

## Build, Test, and Development Commands
Use Maven for all lifecycle tasks:
```bash
mvn clean package           # Compile with annotation processing, emit target/Zea-tcp-0.7.8.jar
mvn test                    # Execute the JUnit 5 suite
mvn exec:java -Dexec.mainClass=com.akakata.Main \
  -Dexec.args="--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
```
The exec goal starts all transports with the required Agrona JVM flag; after packaging, the equivalent manual command uses `java --add-opens ... -cp target/classes:target/dependency/* com.akakata.Main`.

## Coding Style & Naming Conventions
Target Java 17 with 4-space indentation, braces on the same line, and prefer `final` fields. Rely on constructor injection so Dagger auto-generates factories from `ServiceModule`, `ProtocolModule`, and `ServerModule`. Keep package names under `com.akakata.<module>` and use descriptive class names such as `ShardedEventDispatcher` and `SbeProtocol`. Acquire loggers via `LoggerFactory.getLogger` and let Logback handle formatting.

## Testing Guidelines
Write JUnit 5 tests suffixed with `Test`, mirroring the package of the code under test. Mock Netty and Agrona interactions behind interfaces to avoid network dependencies. Run `mvn test` locally before pushing and extend coverage for session lifecycle, dispatcher back-pressure, and protocol interoperability.

## Commit & Pull Request Guidelines
Compose commits with imperative subjects ≤50 characters; add optional body context and issue references such as `Refs #123`. Pull requests should explain motivation, list configuration changes (ports, JVM flags), and document manual verification, attaching logs or screenshots for login or network adjustments.

## Security & Configuration Tips
Do not commit secrets. Document new configuration keys in `src/main/resources/props/conf.properties` using lowercase dot-delimited names. Always run transports with `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED` and validate external ports with network controls before merging.
