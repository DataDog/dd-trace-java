<!-- INSTRUCTIONS FOR ANSWERING QUESTIONS -->
<!--
- Answer each question inline below the question
- You can edit the questions if they're unclear
- Add your answers under each question
- When done, save the file and let me know
-->

## Q1: BackendApi interface scope (from QUESTIONS-1, Q1 - unanswered)

Currently, `BackendApi` interface exposes `okhttp3.RequestBody` and `OkHttpUtils.CustomListener`:

```java
public interface BackendApi {
  <T> T post(
      String uri,
      RequestBody requestBody,  // okhttp3.RequestBody
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable OkHttpUtils.CustomListener requestListener,
      boolean requestCompression)
      throws IOException;
}
```

Should we:
- **Option A**: Change `BackendApi` to use abstract types (e.g., `HttpRequestBody`, `HttpListener`) - this means all callers outside communication module need to update
- **Option B**: Keep `BackendApi` as internal interface (package-private), provide higher-level methods in public API classes that don't expose HTTP types
- **Option C**: Something else?
Option A

## Q2: products/feature-flagging module direct RequestBody usage?

In `ExposureWriterImpl.java`:
```java
final RequestBody requestBody =
    RequestBody.create(okhttp3.MediaType.parse("application/json"), reqBod);
evp.post("exposures", requestBody, stream -> null, null, false);
```

Since you chose Option A for Q7 (remove okhttp dependencies from dependent modules), should feature-flagging:
- **Option A**: Use communication module's abstraction to create request bodies (e.g., `HttpRequestBody.json(bytes)`)
- **Option B**: Pass raw JSON string/bytes to BackendApi, let it handle RequestBody creation internally
- **Option C**: Something else?
Option A

## Q3: Package structure for abstractions?

Where should the new abstractions live?
- **Option A**: New package `datadog.communication.http.client` (interfaces + factory)
  - `datadog.communication.http.client.HttpClient`
  - `datadog.communication.http.client.HttpRequest`
  - `datadog.communication.http.client.HttpResponse`
  - `datadog.communication.http.client.HttpRequestBody`
  - etc.
- **Option B**: Keep in existing `datadog.communication.http` package
- **Option C**: Split by concern:
  - Abstractions in `datadog.communication.http`
  - Implementations in `datadog.communication.http.okhttp` and `datadog.communication.http.jdk`
- **Option D**: Something else?
Option A for the interface and factories, and Option C for the implementations

## Q4: HttpClient factory/builder class name?

What should we name the entry point for creating HttpClient instances?
- **Option A**: `HttpClientFactory` with static factory methods
- **Option B**: `HttpClient.newBuilder()` (static method on interface, returns builder)
- **Option C**: `HttpClientBuilder` as a separate class
- **Option D**: Something else?
Option B

## Q5: Transition strategy for SharedCommunicationObjects?

`SharedCommunicationObjects` has public fields:
```java
public OkHttpClient agentHttpClient;
public HttpUrl agentUrl;
```

Should we:
- **Option A**: Change fields to abstract types immediately, update all consumers in one go
- **Option B**: Add new abstract accessor methods, deprecate public fields, remove in later phase
- **Option C**: Make fields private, expose abstract getters only
- **Option D**: Something else?
Use abstract type and make fields private. Expose them using getters

## Q6: HttpUrl abstraction - what methods needed?

You said "Our own abstraction, except if mapping exactly to java.net.URI methods."

Which approach:
- **Option A**: Create minimal `HttpUrl` abstraction with only methods we need (url(), resolve(), scheme(), etc.), backed by java.net.URI internally
- **Option B**: Use java.net.URI directly everywhere (since OkHttp's HttpUrl is mostly convenience wrapper)
- **Option C**: Create richer abstraction that matches OkHttp's HttpUrl API surface (builder, query params, etc.)
- **Option D**: Something else?
Only a minimal HttpUrl abstraction of the needed (currently used) methods.

## Q7: MockWebServer for tests?

You chose Option A+C for testing (test both implementations + keep MockWebServer).

MockWebServer is OkHttp-specific. Should we:
- **Option A**: Keep using MockWebServer for testing both implementations (it's just a test server)
- **Option B**: Create abstract test server that can use either MockWebServer or jdk.httpserver.HttpServer
- **Option C**: Use MockWebServer for OkHttp tests, jdk.httpserver for JDK client tests
- **Option D**: Something else?
jdk.httpserver.HttpServer if possible, MockServer if easier and cleaner.

## Q8: RequestBody content type handling?

Request bodies have content types (application/msgpack, application/json). Should:
- **Option A**: Include content type in HttpRequestBody abstraction (e.g., `body.contentType()`)
- **Option B**: Pass content type separately to HttpClient/Request (e.g., `request.header("Content-Type", "...")`)
- **Option C**: Infer from factory method (e.g., `HttpRequestBody.json()` implies application/json)
- **Option D**: Something else?
Option B to make JDK http client usage

## Q9: Error handling - checked vs unchecked exceptions?

Current code throws `IOException`. Should the abstraction:
- **Option A**: Keep throwing checked IOException
- **Option B**: Wrap in unchecked exceptions (e.g., `HttpClientException extends RuntimeException`)
- **Option C**: Use both - checked IOException for expected errors, unchecked for programming errors
- **Option D**: Something else?
Option A

## Q10: Implementation selection mechanism?

You chose Option C for Q6 (auto-detect Java version). Specifically:
- **Option A**: Check `System.getProperty("java.version")` and parse
- **Option B**: Use existing `datadog.trace.api.Platform.isJavaVersionAtLeast(11)` or similar
- **Option C**: Try to load JDK 11 HttpClient class, catch ClassNotFoundException if not available
- **Option D**: Something else?
Option B

## Q11: Scope of this refactoring - what's included?

Based on Q7 answer (Option A), you want to update dependent modules. Should we include:

**In scope for this refactoring:**
- [x] Communication module abstraction
- [x] Update remote-config-core to use abstraction
- [x] Update utils/flare-utils to use abstraction
- [x] Update products/feature-flagging to use abstraction
- [x] Update telemetry module to use abstraction
- [x] Update dd-trace-core to use abstraction
- [x] Update all dd-java-agent/* modules that depend on communication

**Or should some be deferred to follow-up work?**

## Q12: UnixDomainSocket and NamedPipe support with JDK HttpClient?

OkHttp supports UDS and named pipes via custom `SocketFactory`. JDK 11 HttpClient added Unix domain socket support in Java 16.

Should we:
- **Option A**: Require Java 16+ for JDK HttpClient implementation when UDS is needed, fallback to OkHttp on Java 11-15
- **Option B**: Only use JDK HttpClient on Java 16+, use OkHttp for Java 8-15
- **Option C**: Keep OkHttp for UDS/named pipes, even when JDK HttpClient is available
- **Option D**: Something else?
Use JDK HttpClient from Java 11. Use com.github.jnr:jnr-unixsocket (already added and used) for Java 11 to 15 and native UDS socket from Java 16 and above

## Q13: HTTP/2 support?

OkHttp supports HTTP/2. JDK HttpClient defaults to HTTP/2. Should we:
- **Option A**: Let implementations use their defaults (HTTP/2 when available)
- **Option B**: Force HTTP/1.1 for consistent behavior
- **Option C**: Make it configurable
- **Option D**: Something else?
Option A

---

## Anything else you'd like to mention?

**Additional context or clarifications:**


<!-- Save this file when you're done -->
