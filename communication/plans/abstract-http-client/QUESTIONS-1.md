<!-- INSTRUCTIONS FOR ANSWERING QUESTIONS -->
<!--
- Answer each question inline below the question
- You can edit the questions if they're unclear
- Add your answers under each question
- When done, save the file and let me know
-->

## Q1: Abstraction Scope - What should remain in communication module vs exposed?

Currently, `BackendApi` interface exposes `okhttp3.RequestBody` and `OkHttpUtils.CustomListener`. Should the abstraction:
- **Option A**: Create abstract types (e.g., `HttpRequestBody`, `HttpListener`) that completely hide OkHttp, requiring all callers to use these abstract types
- **Option B**: Keep the abstraction internal to communication module only, and provide factory/builder methods within communication module to create request bodies (e.g., `OkHttpUtils.msgpackRequestBodyOf()` becomes part of the abstraction)
- **Option C**: Something else?

## Q2: What about SharedCommunicationObjects.agentHttpClient public field?

`SharedCommunicationObjects` exposes `public OkHttpClient agentHttpClient`. This is used by:
- `DefaultConfigurationPoller` in remote-config-core
- `DDAgentFeaturesDiscovery` internally

Should we:
- **Option A**: Change this to an abstract `HttpClient` interface and update all consumers
- **Option B**: Keep it as-is but mark it deprecated, provide a new abstract accessor
- **Option C**: Make it package-private and provide an abstract accessor through a new interface
- **Option D**: Something else?
Option A

## Q3: URL Handling - HttpUrl abstraction needed?

`okhttp3.HttpUrl` is used extensively for URL parsing, building, and resolution. Should we:
- **Option A**: Create our own `HttpUrl` abstraction (e.g., `datadog.communication.http.Url`)
- **Option B**: Use `java.net.URI` everywhere instead
- **Option C**: Keep using `okhttp3.HttpUrl` in public APIs (since it's just a data structure, not tied to OkHttp implementation)
- **Option D**: Something else?
Our own abstraction, except if it's mapping exactly to java.net.URI methods.

## Q4: RequestBody abstraction strategy?

Request bodies need to support:
- MsgPack content (with ByteBuffer lists)
- JSON content (with byte arrays)
- GZIP compression
- Streaming writes to BufferedSink

Should we:
- **Option A**: Create abstract `HttpRequestBody` interface with factory methods in communication module
- **Option B**: Use raw byte[] or InputStream everywhere, handle encoding internally
- **Option C**: Keep RequestBody as an interface but provide both OkHttp and JDK 11 implementations
- **Option D**: Something else?
Option A

## Q5: Response handling abstraction?

Currently `HttpRetryPolicy.shouldRetry()` takes `okhttp3.Response`. Should we:
- **Option A**: Create abstract `HttpResponse` interface
- **Option B**: Extract only what's needed (status code, headers) into a simple data class
- **Option C**: Change HttpRetryPolicy to work with status codes and exceptions only
- **Option D**: Something else?
Option A

## Q6: JDK 11 HttpClient - Runtime detection or compile-time?

How should we detect and select between OkHttp and JDK 11 HttpClient?
- **Option A**: Runtime detection via reflection - try to load JDK 11 classes, fallback to OkHttp
- **Option B**: Configuration property (e.g., `dd.http.client.implementation=okhttp|jdk11`)
- **Option C**: Auto-detect Java version at runtime (Java 11+ uses JDK client, older uses OkHttp)
- **Option D**: Something else?
Option C, using JavaVirtualMachine

## Q7: Module dependencies - Should remote-config and flare-utils depend on okhttp?

Currently these modules have their own OkHttp dependencies:
- `remote-config-core` - depends on okhttp, creates Request/RequestBody
- `utils/flare-utils` - depends on okhttp, uses OkHttpClient

Should we:
- **Option A**: Remove their okhttp dependencies, make them use communication module's abstractions
- **Option B**: Keep their okhttp dependencies but ensure they only use it internally (not exposed)
- **Option C**: Leave them as-is (out of scope for this refactoring)
- **Option D**: Something else?
Option A

## Q8: EventListener (CustomListener) abstraction?

`OkHttpUtils.CustomListener` extends `okhttp3.EventListener` for timing/monitoring. Should we:
- **Option A**: Create abstract listener interface for HTTP events (request start, end, failure, etc.)
- **Option B**: Remove this feature from the abstraction (OkHttp-specific optimization)
- **Option C**: Keep it but make it optional/internal to the implementation
- **Option D**: Something else?
Option A

## Q9: Connection pool, timeouts, proxy configuration?

OkHttp's `buildHttpClient()` configures:
- Connection pooling
- Timeouts (connect, read, write)
- Proxy settings (HTTP proxy, auth)
- Unix domain sockets / Named pipes
- TLS/SSL settings

Should the abstraction:
- **Option A**: Expose all these as abstract configuration options
- **Option B**: Only expose common options (timeouts, proxy), hide advanced features
- **Option C**: Use a builder pattern with sensible defaults
- **Option D**: Something else?
Option C

## Q10: Backwards compatibility - API breakage acceptable?

This refactoring will likely require changing public APIs. Is it acceptable to:
- **Option A**: Make breaking changes to communication module's API (bump major version)
- **Option B**: Maintain backwards compatibility via deprecated methods/classes
- **Option C**: Create a new parallel API (v2) alongside the existing one
- **Option D**: Something else?
You can break the communication module API. You should not break API from the dd-trace-api module.

## Q11: Testing strategy?

Should we:
- **Option A**: Test both OkHttp and JDK 11 implementations with the same test suite
- **Option B**: Mock the abstraction layer for existing tests
- **Option C**: Keep using MockWebServer for integration tests
- **Option D**: All of the above?
Option A and C

## Q12: Okio dependency?

OkHttp uses Okio for efficient I/O (BufferedSink, BufferedSource). Should we:
- **Option A**: Keep Okio as a dependency (it's useful regardless of HTTP client)
- **Option B**: Remove Okio, use standard Java streams
- **Option C**: Abstract Okio types as well (e.g., create our own BufferedSink interface)
- **Option D**: Something else?
Only use Okio for the OkHttp client implementation. It should not be use elsewhere and not be part of the APIs

## Q13: Performance considerations?

Are there specific performance requirements or concerns?
- Should the abstraction be zero-overhead?
- Are there hot paths where allocation/indirection is critical?
- Any benchmarks we should maintain/not regress?
The abstraction should be a light as possible. It should not introduce performance regression and limit allocations.

## Q14: Retry logic location?

Currently `OkHttpUtils.sendWithRetries()` handles retry logic with OkHttp. Should retry logic:
- **Option A**: Stay in the abstraction layer (implementation-agnostic)
- **Option B**: Move to a higher level (BackendApi implementations)
- **Option C**: Be part of the HttpClient abstraction interface
- **Option D**: Something else?
It can be part of the HttpClient abstraction interface

## Q15: Feature parity with JDK 11 HttpClient?

Are all current OkHttp features required to work with JDK 11 HttpClient, or can we:
- Drop support for some features when using JDK 11 client?
- Accept different behavior (e.g., connection pooling, retry semantics)?
- Maintain strict feature parity?
Maintain strict feature parity

---

## Anything else you'd like to mention?

**Additional context or clarifications:**
The refactoring should not introduce regression nor behavior change for the communication API consumers.

<!-- Save this file when you're done -->
