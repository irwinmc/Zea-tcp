# Core Components and Security

This guide covers the component injection and security policies introduced in 0.8.2.
Dependency versions are declared in [pom.xml](../pom.xml).

## Properties and Assembly

`ConfigurationManager` already loads ordinary classpath `.properties` files.
Removing Spring did not remove that loader. Properties supply values such as ports,
queue budgets and timeouts; they do not select an application's Java implementations.

Dagger still assembles the internal graph at compile time. `ServerComponents`
supplies application choices before `ServerContext.initialize()` builds that graph.
No Spring, reflective bean scanning, YAML parser or additional container is required.
An application using its own container can pass its already-created verifier,
token service and factory dependencies through the same API.

```java
ServerComponents components = ServerComponents.builder()
        .credentialsVerifier(accountVerifier)
        .securityService(applicationTokens)
        .gameFactory(config -> new ApplicationGame(accountRepository,
                config.getInt("game.queue.capacity", 32768),
                config.getLong("game.queue.bytes", 67108864)))
        .sessionServiceFactory((config, verifier) -> new SessionService(verifier,
                true, config.getInt("session.maximum", 10000)))
        .loginServiceFactory(LoginService::new)
        .build();

ServerContext context = new ServerContext("props/conf-prod.properties", components);
context.initialize();
```

`accountVerifier`, `applicationTokens`, `accountRepository` and `ApplicationGame`
are application implementations in this integration fragment. Omit any builder
method to use its configured framework default. A custom Game factory must honor
its desired queue budgets itself; the framework does not rewrite custom resources.
A custom registry or token provider similarly owns its application's policy.

| Override | Input / Ownership |
| --- | --- |
| `credentialsVerifier(...)` | Application-owned fast verifier; borrowed login buffer |
| `securityService(...)` | Application-owned token implementation; skips default token configuration |
| `gameFactory(...)` | Loaded configuration; returned Game is context-owned |
| `sessionServiceFactory(...)` | Configuration and selected verifier; returned registry is context-owned |
| `loginServiceFactory(...)` | Selected registry and token service; application coordinator |

Factories are lazy singleton providers. A factory must return a nonnull resource
and clean up any allocation it makes before throwing. Resources returned by earlier
factories are retained by the context and closed if later initialization fails.
Do not return a Game or SessionService shared with another owner: the context
will close it. Independently owned database clients or other resources captured by
factories remain the application's responsibility. Startup and shutdown hooks can
manage those resources; a startup failure also closes the initialized context.

`getBean()` exposes `Game`, `SessionService`, `ServerManager`, `CredentialsVerifier`,
`SecurityService`, `LoginService`, `OutboundBudget` and `BlockingTaskExecutor`.
It returns retained resources without creating new objects after shutdown.
Configuration is available through `getConfigManager()`. This API is a fixed
resource lookup, not a general-purpose container. A custom Game dispatcher need
not be a `NettyEventDispatcher`; its specialized metrics are absent if it is not.

## Authentication Defaults

The default verifier denies every login. Supply `CredentialsVerifier` for an
application that accepts authenticated users. It must validate the payload and
return a stable credential key for the same account when reconnect replacement
is required. Returning a client-provided identity without verification is unsafe.
The verifier borrows the input buffer and must not release it or retain it for
unbounded asynchronous work.

```properties
security.allow.anonymous=false
```

Only explicit `true` enables `SimpleCredentialsVerifier`, which ignores the input
and creates a new random identity. This supports local demonstrations and never
proves account ownership. Invalid boolean spellings fail initialization.
`TestServer`, `AdvancedTestServer`, `WebSocketOnlyServer` and the embedded network
load driver explicitly opt into anonymous mode; application defaults remain closed.

Verification and login callbacks currently run synchronously on the Netty I/O
thread. Verify a preissued credential or a local cached identity promptly. Perform
remote account verification outside this pipeline, or implement an application
login flow that offloads it safely. See [slow business operations](SLOW_BUSINESS.md).

## Token Keys and Expiry

```properties
# Name of an environment variable containing a standard Base64 AES key.
security.token.key.environment=ZEA_TOKEN_KEY
security.token.ttl.seconds=3600
```

Provision `ZEA_TOKEN_KEY` through your deployment secret store. Its decoded length
must be 16, 24 or 32 bytes. Put only the variable name in properties. A named but
missing, blank, malformed or wrongly sized secret fails initialization; errors
never include the secret value. The TTL must be a positive whole number of seconds.

A blank environment-variable name, the bundled default, creates a random 256-bit
key for that SecurityService instance. Its tokens stop working after a restart and
are not valid in another instance. Production applications needing persistent or
shared verification must configure or inject their key. Explicit construction is
also supported with `SecurityService(SecretKey, Duration)`; applications needing
other token formats or key rotation can supply their own SecurityService subclass.

Tokens have a `v1.` prefix, random 12-byte IV, AES-GCM authentication tag, encrypted
expiry and encrypted identity. The service uses UTF-8, rejects oversized inputs,
checks authentication before identity use and rejects tokens at their expiry time.
The legacy fixed library key has been removed. Former tokens are not accepted by
this service. The static one-argument `AesGcmCipher` conveniences now use a random
process-local key; explicit-key overloads are preferred for persistent ciphertext.

`verifyToken()` returns a credential identity, not permission to execute a game
command. The application's verifier still controls reconnect validation,
authorization and revocation. Generating tokens does not automatically install
Token-based login. TCP login returns the issued token; the existing WebSocket
success frame still returns only its success opcode.

## Listener Exposure

```properties
tcp.bind.host=0.0.0.0
http.bind.host=127.0.0.1
web.socket.bind.host=0.0.0.0
```

The bundled configuration explicitly binds HTTP monitoring to loopback. Set a specific private address for a
monitoring agent on another host, or explicitly choose a wildcard address when the
network boundary is controlled. Explicit port overrides preserve the configured
bind host. All three listener ports and bind hosts are required; missing or blank
values, malformed/out-of-range ports and unresolvable hosts fail initialization.
No key name is inferred from another key, and no listener address or port is supplied
as a fallback. Configure every listener, including disabled ones, because the
component constructs all server objects. `/health` and `/metrics` have no built-in request authentication.

The supplied pipelines are plaintext TCP, HTTP and WebSocket. Protect production
connections with an application TLS pipeline or a suitable TLS gateway; setting a
port to 443 does not enable TLS. Restrict monitoring access at the deployment
boundary. Neither anonymous credentials nor token encryption encrypts transport
traffic.
