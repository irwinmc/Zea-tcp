# Project Instructions

These instructions apply to this repository and its subdirectories. Follow the
user's current requirements and preserve unrelated work already in the checkout.

## Project Structure

- Zea TCP Transport is a Java 21 game-server foundation built on Netty.
- Use the existing Dagger assembly and classpath properties loader. Application
  implementations are selected through `ServerComponents`.
- The normal application owns one Game. Introduce room management only when the
  requested behavior requires it.
- Read `pom.xml` for dependency and plugin versions. Keep dependency additions
  small and justified; do not reintroduce Agrona or JVM module-access flags.
- Preserve established public method names such as `Session.onEvent()`,
  `Game.send()` and `MessageSender.sendMessage()`. Improve their implementation
  instead of adding parallel aliases or renaming them without a task requirement.

## Java Style

- Use four spaces for indentation.
- Every `if`, `else`, `for`, enhanced `for`, `while` and `do` body must use braces,
  even for a single `return`, `break`, assignment or method call. Each branch of an
  `else if` chain must also have a braced body.
- Write control-flow blocks across multiple lines. Do not compress methods,
  `try`/`catch`, synchronized blocks or block lambdas containing statements onto
  one line. Do not put several statements on one line.
- Apply the same style to production code, regression tests and executable examples.
- Use `//` for ordinary single-line comments. JavaDoc must use standard multiline
  `/** ... */` blocks; never write one-line JavaDoc.
- Separate members and logical steps with blank lines. Attach JavaDoc directly to
  its declaration and describe behavior, constraints and ownership accurately.
- Write new comments, JavaDoc, README content and documentation in English.
  Document parameters, return values and relevant exceptions; use multiline
  `{@inheritDoc}` JavaDoc where inherited documentation is sufficient.

```java
if (closed) {
    return;
}

for (Task<?> task : outstanding) {
    task.result.cancel(true);
}
```

## Configuration and Security

- Read listener settings using their exact configuration keys. Do not infer a
  bind-host key from a port key or silently supply a listener endpoint.
- All three listener port/address pairs are required, including disabled listeners
  because the current component constructs every server:
  `tcp.port` / `tcp.bind.host`, `http.port` / `http.bind.host`, and
  `web.socket.port` / `web.socket.bind.host`.
- Reject missing or blank required settings, malformed or out-of-range ports and
  unresolvable addresses. Port zero is an explicit request for an ephemeral port.
  Errors must identify the configuration key.
- Optional resource settings may use their declared defaults only when absent.
  Malformed present numeric or boolean values must fail instead of falling back.
- Keep related properties grouped in the bundled configuration and examples.
  Update configuration documentation and fixtures when a setting changes.
- Keep authentication closed by default. Anonymous credentials require an explicit
  development or test choice. Do not introduce fixed token secrets, log credentials
  or commit secret values; provision persistent keys through environment variables
  or an injected token service.
- Preserve the bundled HTTP loopback address. Changing an HTTP port does not change
  its configured bind host or enable TLS.

## Concurrency and Resource Ownership

- Preserve ordered delivery on each Session executor and the serial Game executor.
  Player handlers must remain isolated by session instance identity.
- Closing one session releases its registrations and queued work without closing
  an executor shared by other sessions. Old-session cleanup must not remove a
  replacement session for the same credential identity.
- The context owns its returned Game, SessionService, blocking workers and network
  resources. Clean up partial initialization and startup failures, and make closure
  idempotent. Keep application-owned dependencies under their documented owner.
- Do not block I/O loops or ordered callbacks with database calls, remote calls,
  sleeps or synchronous future waits. Never wait for work queued to the same executor.
- Use bounded offloading for slow operations. Copy borrowed native input into
  detached data, then post results through the original Session or Game event API.
  Validate the original session instance and operation sequence before applying a
  late result; worker completion order is not request order.
- Respect Netty reference counting. Capture retained payload references, release
  accepted ownership after completion or discard, and handle rejected submissions
  according to the API's documented ownership contract.
- Keep count and byte admission bounded, including running work. A cancelled or
  timed-out future must not return credits while execution still uses the resource.
  Rejected work must not run inline on the submitting thread.
- Broadcast recipients must be explicit. Do not restore automatic Game-wide
  broadcasting of every incoming player message.

## Verification

- Use `./mvnw` and Java 21. Compile before generating JavaDoc so Dagger-generated
  component types are available in the same Maven invocation.
- Add meaningful regression tests for behavior, concurrency, lifecycle, protocol,
  configuration and resource-ownership changes. Exercise rejection and cleanup as
  well as successful paths; prefer barriers and latches over arbitrary sleeps.
- Tests that terminate `ServerContext` or process-wide dispatchers must use isolated
  JVMs. Do not reset terminal global lifecycle state to make tests pass.
- For formatting-only changes, check braces, multiline layout, syntax and relevant
  existing tests. Do not add tests that merely assert formatting.
- Run the full checks for changes affecting shared components, lifecycle or protocols:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify javadoc:javadoc javadoc:test-javadoc
git diff --check
```

- JavaDoc uses strict doclint with errors and warnings treated as failures, including
  private members and test sources. Do not edit generated Dagger files or `target/`.
- If IntelliJ compilation races with Maven output, verify in a temporary snapshot
  outside the checkout. Exclude `.git`, `target`, `.idea` and local secret-bearing
  configuration such as `remote-test.local.json`; preserve that file in the checkout.

## Documentation and Git

- Keep README, relevant `docs/` guides, JavaDoc and examples consistent with actual
  implementation and dependency versions. Describe unreleased changes accurately.
- Consult [event ownership](docs/EVENT_LOOP.md), [configuration](docs/CONFIGURATION.md),
  [core components and security](docs/CORE_COMPONENTS.md), and
  [slow business operations](docs/SLOW_BUSINESS.md) when touching those areas.
- Inspect the working tree before editing. Preserve unrelated user changes and keep
  commits focused on the authorized work. Do not commit, push or publish without
  authorization for that action.
- Use exactly `Zea TCP <version>` for every GitHub Release title, for example
  `Zea TCP 0.8.2`. Read the version from the POM at the release tag, without the
  tag's `v` prefix or a descriptive subtitle. Put change summaries and migration
  details in the release notes.
- Use the Maven wrapper for releases and follow [publishing](docs/PUBLISHING.md).
  Run `.github/scripts/test-central-publishing.py` when changing the publishing
  workflow or release profile. Never replace an already published Maven version or
  alter release tags as part of an unrelated code change.
