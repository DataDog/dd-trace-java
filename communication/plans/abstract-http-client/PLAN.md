# HTTP Client Abstraction Implementation Plan

**Overall Progress:** `47%`

## Overview

Refactor the `:communication` module to introduce an abstraction layer for HTTP clients, enabling the ability to swap OkHttp with JDK 11's HttpClient. The abstraction will be completely internal to the communication module and its direct dependents.

**Key Goals:**
- No OkHttp API exposure outside communication module
- Auto-detect Java version and use JDK HttpClient on Java 11+ (configurable)
- Strict feature parity between implementations
- No performance regression
- No behavior changes for consumers

**Configuration:**
- System property: `dd.http.client.implementation` with values: `auto` (default), `okhttp`, `jdk`
- `auto` = use JDK HttpClient on Java 11+, OkHttp otherwise

---

## Phase 1: Core Abstractions (Foundation)

### Task 1.1: Create HttpUrl abstraction

- [x] 🟩 **Define HttpUrl interface**
  - [x] 🟩 Write test: HttpUrl interface contract tests
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpUrl` interface
    - [x] 🟩 Methods: `url()`, `resolve(String)`, `scheme()`, `host()`, `port()`
    - [x] 🟩 Static factory: `HttpUrl.parse(String)`
    - [x] 🟩 Builder pattern: `HttpUrl.builder()` (renamed from newBuilder)
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpUrl*"`
  - [x] 🟩 Update PLAN.md

- [x] 🟩 **Create HttpUrl implementations**
  - [x] 🟩 Write test: OkHttpUrl adapter tests (via contract tests)
  - [x] 🟩 Implement: `datadog.communication.http.okhttp.OkHttpUrl` (wraps okhttp3.HttpUrl)
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpUrl` (wraps java.net.URI) (completed in Phase 4)
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpUrl*"`
  - [x] 🟩 Update PLAN.md

### Task 1.2: Create HttpRequestBody abstraction

- [x] 🟩 **Define HttpRequestBody interface**
  - [x] 🟩 Write test: HttpRequestBody interface contract tests
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpRequestBody` interface
    - [x] 🟩 Method: `writeTo(OutputStream)` for streaming
    - [x] 🟩 Method: `contentLength()` returns long (-1 if unknown)
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpRequestBody*"`
  - [x] 🟩 Update PLAN.md

- [x] 🟩 **Create HttpRequestBody factory methods**
  - [x] 🟩 Write test: Test msgpack body creation
  - [x] 🟩 Implement: `HttpRequestBody.msgpack(List<ByteBuffer>)`
  - [x] 🟩 Write test: Test String body creation
  - [x] 🟩 Implement: `HttpRequestBody.of(String)` (replaces json factory, content-type set via headers)
  - [x] 🟩 Write test: Test GZIP compression decorator
  - [x] 🟩 Implement: `HttpRequestBody.gzip(HttpRequestBody)`
  - [x] 🟩 Write test: Test multipart body creation (minimal for flare-utils)
  - [x] 🟩 Implement: `HttpRequestBody.multipart()` returns builder for multipart/form-data
    - [x] 🟩 MultipartBuilder with addFormDataPart(name, value) and addFormDataPart(name, filename, body)
    - [x] 🟩 Delegated to OkHttp's MultipartBody.Builder
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpRequestBody*"`
  - [x] 🟩 Update PLAN.md

### Task 1.3: Create HttpResponse abstraction

- [x] 🟩 **Define HttpResponse interface**
  - [x] 🟩 Write test: HttpResponse interface contract tests
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpResponse` interface
    - [x] 🟩 Method: `code()` returns int
    - [x] 🟩 Method: `isSuccessful()` returns boolean
    - [x] 🟩 Method: `header(String)` returns String (case-insensitive)
    - [x] 🟩 Method: `headers(String)` returns List<String> (case-insensitive)
    - [x] 🟩 Method: `body()` returns InputStream
    - [x] 🟩 Method: `close()` for resource cleanup
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpResponse*"`
  - [x] 🟩 Update PLAN.md

- [x] 🟩 **Create HttpResponse implementations**
  - [x] 🟩 Write test: OkHttpResponse adapter tests (9 tests covering all methods)
  - [x] 🟩 Implement: `datadog.communication.http.okhttp.OkHttpResponse` (wraps okhttp3.Response)
  - [ ] 🟥 Write test: JdkHttpResponse adapter tests (deferred to Phase 4)
  - [ ] 🟥 Implement: `datadog.communication.http.jdk.JdkHttpResponse` (wraps HttpResponse<InputStream>) (deferred to Phase 4)
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpResponse*"`
  - [x] 🟩 Update PLAN.md

### Task 1.4: Create HttpRequest abstraction

- [x] 🟩 **Define HttpRequest and Builder**
  - [x] 🟩 Write test: HttpRequest builder tests (11 tests)
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpRequest` interface
    - [x] 🟩 Method: `url()` returns HttpUrl
    - [x] 🟩 Method: `method()` returns String
    - [x] 🟩 Method: `header(String)` returns String (single value)
    - [x] 🟩 Method: `headers(String)` returns List<String> (all values)
    - [x] 🟩 Method: `body()` returns HttpRequestBody
    - [x] 🟩 Method: `tag(Class<T>)` returns T for metadata
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpRequest.Builder`
    - [x] 🟩 Method: `url(HttpUrl)`, `url(String)`
    - [x] 🟩 Method: `get()`, `post(HttpRequestBody)`, `put(HttpRequestBody)`
    - [x] 🟩 Method: `header(String, String)` (replaces), `addHeader(String, String)` (appends)
    - [x] 🟩 Method: `tag(Class<T>, T)` for CustomListener support
    - [x] 🟩 Method: `build()` returns HttpRequest
  - [x] 🟩 Implement: OkHttpRequest adapter and builder
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpRequest*"`
  - [x] 🟩 Update PLAN.md

### Task 1.5: Create HttpListener abstraction

- [x] 🟩 **Define HttpListener interface**
  - [x] 🟩 Write test: HttpListener contract tests (4 tests)
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpListener` interface
    - [x] 🟩 Method: `onRequestStart(HttpRequest)` - Called before request is sent
    - [x] 🟩 Method: `onRequestEnd(HttpRequest, HttpResponse)` - Called on successful response
    - [x] 🟩 Method: `onRequestFailure(HttpRequest, IOException)` - Called on request failure
    - [x] 🟩 Constant: `HttpListener.NONE` - No-op implementation
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpListener*"`
  - [x] 🟩 Update PLAN.md
  - [x] 🟩 Note: Replaces OkHttp CustomListener tag pattern with clean abstraction

---

## Phase 2: HttpClient Interface & Builder

### Task 2.1: Define HttpClient interface

- [x] 🟩 **Create HttpClient interface**
  - [x] 🟩 Write test: HttpClient contract tests (6 tests with MockWebServer)
  - [x] 🟩 Implement: Create `datadog.communication.http.client.HttpClient` interface
    - [x] 🟩 Method: `execute(HttpRequest)` returns HttpResponse
    - [x] 🟩 Method: `close()` for resource cleanup
    - [x] 🟩 Static method: `HttpClient.newBuilder()` returns Builder
  - [x] 🟩 Implement: OkHttpClient adapter wrapping okhttp3.OkHttpClient
  - [x] 🟩 Implement: OkHttpClientBuilder for constructing instances
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpClient*"`
  - [x] 🟩 Update PLAN.md
  - [x] 🟩 Note: executeWithRetries() deferred - can use helper method or builder option

### Task 2.2: Define HttpClient.Builder

- [x] 🟩 **Create HttpClient.Builder interface**
  - [x] 🟩 Write test: Builder configuration tests (15 tests)
  - [x] 🟩 Implement: Static method `HttpClient.newBuilder()` returns Builder (already in 2.1)
  - [x] 🟩 Implement: Builder methods
    - [x] 🟩 `connectTimeout(long, TimeUnit)`
    - [x] 🟩 `readTimeout(long, TimeUnit)`
    - [x] 🟩 `writeTimeout(long, TimeUnit)`
    - [x] 🟩 `proxy(Proxy)`
    - [x] 🟩 `proxyAuthenticator(String username, String password)`
    - [x] 🟩 `unixDomainSocket(File)`
    - [x] 🟩 `namedPipe(String)`
    - [x] 🟩 `clearText(boolean)` for HTTP vs HTTPS
    - [x] 🟩 `retryOnConnectionFailure(boolean)`
    - [x] 🟩 `maxRequests(int)`
    - [x] 🟩 `dispatcher(Executor)` - requires ExecutorService
    - [x] 🟩 `eventListener(HttpListener)` - bridges to OkHttp EventListener
    - [x] 🟩 `build()` returns HttpClient
  - [x] 🟩 Implement: OkHttpEventListenerAdapter to bridge HttpListener to OkHttp
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpClientBuilder*"`
  - [x] 🟩 Update PLAN.md

### Task 2.3: Implementation selection logic

- [x] 🟩 **Create HttpClientFactory with configuration support**
  - [x] 🟩 Write test: Test Java version detection (9 tests)
  - [x] 🟩 Write test: Test configuration property parsing
  - [x] 🟩 Implement: `datadog.communication.http.client.HttpClientFactory`
    - [x] 🟩 Read system property `dd.http.client.implementation` (values: `auto`, `okhttp`, `jdk`)
    - [x] 🟩 If `auto` (default): Use `JavaVirtualMachine.isJavaVersionAtLeast(11)` to select
    - [x] 🟩 If `okhttp`: Force OkHttp implementation
    - [x] 🟩 If `jdk`: Log warning, fallback to OkHttp (JDK client in Phase 4)
    - [x] 🟩 Return OkHttp builder for Java < 11 or when configured
    - [x] 🟩 Return OkHttp builder for now (JDK builder in Phase 4)
  - [x] 🟩 Write test: Test forced implementation selection
  - [x] 🟩 Write test: Test case-insensitive config, invalid values
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpClientFactory*"`
  - [x] 🟩 Update PLAN.md
  - [x] 🟩 Note: JDK HttpClient will be implemented in Phase 4, currently falls back to OkHttp

---

## Phase 3: OkHttp Implementation

**Note:** Phase 3 was completed during Phases 1 and 2, as the OkHttp adapters were implemented alongside the interfaces.

### Task 3.1: Implement OkHttpClient adapter

- [x] 🟩 **Create OkHttpClient adapter**
  - [x] 🟩 Write test: Test execute() wraps OkHttp calls (via HttpClientTest)
  - [x] 🟩 Implement: `datadog.communication.http.okhttp.OkHttpClient` implements HttpClient
    - [x] 🟩 Wrap existing `okhttp3.OkHttpClient`
    - [x] 🟩 Implement `execute()` by delegating to OkHttp
    - [x] 🟩 Convert HttpRequest to okhttp3.Request (via OkHttpRequest.unwrap())
    - [x] 🟩 Convert okhttp3.Response to HttpResponse (via OkHttpResponse.wrap())
  - [x] 🟩 Implement: `close()` for resource cleanup
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpClient*"`
  - [x] 🟩 Update PLAN.md
  - [x] 🟩 Note: Implemented in Phase 2.1 alongside HttpClient interface

### Task 3.2: Implement OkHttpClient.Builder

- [x] 🟩 **Create OkHttpClientBuilder**
  - [x] 🟩 Write test: Test builder configuration mapping (via HttpClientBuilderTest - 15 tests)
  - [x] 🟩 Implement: `datadog.communication.http.okhttp.OkHttpClient.OkHttpClientBuilder` implements HttpClient.Builder
    - [x] 🟩 Delegate to okhttp3.OkHttpClient.Builder internally
    - [x] 🟩 Map timeout settings (connect, read, write)
    - [x] 🟩 Map proxy settings with authentication
    - [x] 🟩 Map UDS/named pipe via UnixDomainSocketFactory and NamedPipeSocketFactory
    - [x] 🟩 Map connection pool settings
    - [x] 🟩 Map dispatcher/executor settings (requires ExecutorService)
    - [x] 🟩 Map event listener to OkHttpEventListenerAdapter
  - [x] 🟩 Write test: Test build() returns OkHttpClient
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpClientBuilder*"`
  - [x] 🟩 Update PLAN.md
  - [x] 🟩 Note: Implemented in Phase 2.2 alongside HttpClient.Builder interface

### Task 3.3: OkHttp request body adapters

- [x] 🟩 **Implement OkHttp RequestBody adapters**
  - [x] 🟩 Write test: Test HttpRequestBody adapters (via HttpRequestBodyTest - 12 tests)
  - [x] 🟩 Implement: `datadog.communication.http.okhttp.OkHttpRequestBody` adapters
    - [x] 🟩 Adapter wraps HttpRequestBody as okhttp3.RequestBody
    - [x] 🟩 Override `writeTo(BufferedSink)` to call `HttpRequestBody.writeTo(OutputStream)`
    - [x] 🟩 Use Okio.buffer(Okio.sink(outputStream)) for streaming
    - [x] 🟩 Support msgpack, String, gzip, and multipart bodies
  - [x] 🟩 Test: Run `./gradlew :communication:test --tests "*HttpRequestBody*"`
  - [x] 🟩 Update PLAN.md
  - [x] 🟩 Note: Implemented in Phase 1.2 alongside HttpRequestBody interface

---

## Phase 4: JDK HttpClient Implementation

### Task 4.1: Implement JdkHttpClient adapter

- [x] 🟩 **Create JdkHttpClient**
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpClient` implements HttpClient
    - [x] 🟩 Wrap `java.net.http.HttpClient`
    - [x] 🟩 Implement `execute()` by delegating to JDK HttpClient
    - [x] 🟩 Convert HttpRequest to java.net.http.HttpRequest (via JdkHttpRequest wrapper)
    - [x] 🟩 Convert java.net.http.HttpResponse to HttpResponse (via JdkHttpResponse wrapper)
    - [x] 🟩 Use BodyHandlers.ofInputStream() for response body
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpResponse` wrapper
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpRequest` wrapper
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpUrl` wrapper
  - [x] 🟩 Update factories to use reflection for dynamic loading
  - [x] 🟩 Test: All 243 tests passing
  - [x] 🟩 Update PLAN.md

### Task 4.2: Implement JdkHttpClient.Builder

- [x] 🟩 **Create JdkHttpClientBuilder**
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpClient.JdkHttpClientBuilder` implements HttpClient.Builder
    - [x] 🟩 Delegate to java.net.http.HttpClient.Builder internally
    - [x] 🟩 Map timeout settings using `.connectTimeout(Duration)`
    - [x] 🟩 Map proxy settings using `.proxy(ProxySelector)`
    - [x] 🟩 Map connection pool settings
    - [x] 🟩 Map redirect policy
    - [x] 🟩 Map event listener to JdkHttpEventListenerAdapter
  - [x] 🟩 Configure Java 11 source set in build.gradle.kts
    - [x] 🟩 Create main_java11 source set
    - [x] 🟩 Configure Java 11 compilation targeting Java 8 bytecode
    - [x] 🟩 Include Java 11 output in final jar
  - [x] 🟩 Test: All 243 tests passing
  - [x] 🟩 Update PLAN.md

### Task 4.3: JDK Unix Domain Socket support

- [ ] 🟥 **Implement UDS support for JDK HttpClient**
  - [ ] 🟥 Write test: Test UDS connection on Java 11-15 using jnr-unixsocket
  - [ ] 🟥 Implement: UDS support via jnr-unixsocket for Java 11-15
    - [ ] 🟥 Use `Platform.isJavaVersionAtLeast(16)` to detect version
    - [ ] 🟥 Fallback to jnr-unixsocket for Java 11-15
  - [ ] 🟥 Write test: Test native UDS on Java 16+
  - [ ] 🟥 Implement: Native UDS using StandardProtocolFamily.UNIX for Java 16+
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*JdkUdsSupport*"`
  - [ ] 🟥 Update PLAN.md

### Task 4.4: JDK request body publishers

- [x] 🟩 **Implement JDK BodyPublisher adapters**
  - [x] 🟩 Implement: `datadog.communication.http.jdk.JdkHttpRequestBody` with BodyPublisher adapters
    - [x] 🟩 ofString() - String body using UTF-8 encoding
    - [x] 🟩 ofMsgpack() - MessagePack ByteBuffer list body
    - [x] 🟩 ofGzip() - Gzip compression wrapper using GZIPOutputStream
    - [x] 🟩 multipartBuilder() - Multipart form data builder (RFC 7578)
    - [x] 🟩 wrap() - Generic HttpRequestBody to BodyPublisher adapter
  - [x] 🟩 Implement: JdkMultipartBuilder for multipart/form-data
    - [x] 🟩 addFormDataPart(name, value) - Simple form fields
    - [x] 🟩 addFormDataPart(name, filename, body) - File uploads
    - [x] 🟩 Implements RFC 7578 format with boundaries
  - [x] 🟩 Test: All 243 tests passing
  - [x] 🟩 Update PLAN.md

---

## Phase 5: Update Communication Module Internals

### Task 5.1: Update HttpRetryPolicy

- [x] 🟩 **Refactor HttpRetryPolicy to use HttpResponse abstraction**
  - [x] 🟩 Updated tests to wrap okhttp3.Response with OkHttpResponse
  - [x] 🟩 Implement: Changed `shouldRetry(okhttp3.Response)` to `shouldRetry(HttpResponse)`
  - [x] 🟩 Updated OkHttpUtils to wrap responses before calling shouldRetry()
  - [x] 🟩 Test: All 243 tests passing
  - [x] 🟩 Update PLAN.md

### Task 5.2: Update OkHttpUtils

- [x] ✅ **Refactor OkHttpUtils to use abstractions** (Commit: 92eef03f52)
  - [x] ✅ Implement: Change return type from OkHttpClient to HttpClient
  - [x] ✅ Implement: Change prepareRequest to return HttpRequest.Builder using abstract API
  - [x] ✅ Implement: Update msgpackRequestBodyOf(), jsonRequestBodyOf(), gzippedRequestBodyOf() to return HttpRequestBody
  - [x] ✅ Implement: Change sendWithRetries signature to use HttpClient and HttpRequest
  - [x] ✅ Implement: Use HttpClient.execute() instead of OkHttp newCall()
  - [ ] 🟥 Test: Fix failing tests (33 out of 243 tests failing - expected after API changes)
  - [x] ✅ Update PLAN.md

### Task 5.3: Update SharedCommunicationObjects

- [x] ✅ **Refactor SharedCommunicationObjects** (Commit: 92eef03f52 - Partial)
  - [x] ✅ Implement: Change `public HttpClient agentHttpClient` (kept public for compatibility)
  - [x] ✅ Implement: Change `public HttpUrl agentUrl` (kept public for compatibility)
  - [x] ✅ Implement: Change `private HttpClient intakeHttpClient`
  - [x] ✅ Implement: Update `getIntakeHttpClient()` to return HttpClient
  - [ ] 🟥 Consider: Make fields private and add getters for better encapsulation
  - [ ] 🟥 Test: Fix failing tests
  - [x] ✅ Update PLAN.md

### Task 5.4: Update BackendApi interface

- [x] ✅ **Refactor BackendApi to use abstractions** (Commit: 92eef03f52)
  - [x] ✅ Implement: Replace `okhttp3.RequestBody` with `HttpRequestBody`
  - [ ] 🟥 TODO: Replace `OkHttpUtils.CustomListener` with `HttpListener` (deferred)
  - [ ] 🟥 Test: Fix failing tests
  - [x] ✅ Update PLAN.md

### Task 5.5: Update IntakeApi

- [x] ✅ **Refactor IntakeApi to use HttpClient** (Commit: 92eef03f52)
  - [x] ✅ Implement: Change constructor parameter from `OkHttpClient` to `HttpClient`
  - [x] ✅ Implement: Change `HttpUrl` to abstract `HttpUrl`
  - [x] ✅ Implement: Use HttpRequest.Builder for request construction
  - [x] ✅ Implement: Update post() method to use HttpRequestBody
  - [ ] 🟥 Test: Fix failing tests
  - [x] ✅ Update PLAN.md

### Task 5.6: Update EvpProxyApi

- [x] ✅ **Refactor EvpProxyApi to use HttpClient** (Commit: 92eef03f52)
  - [x] ✅ Implement: Change constructor parameter from `OkHttpClient` to `HttpClient`
  - [x] ✅ Implement: Change `HttpUrl` to abstract `HttpUrl`
  - [x] ✅ Implement: Use HttpRequest.Builder for request construction
  - [ ] 🟥 Implement: Update post() method to use HttpRequestBody
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*EvpProxyApi*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.7: Update DDAgentFeaturesDiscovery

- [x] ✅ **Refactor DDAgentFeaturesDiscovery to use HttpClient** (Commit: 92eef03f52)
  - [x] ✅ Implement: Change constructor parameter from `OkHttpClient` to `HttpClient`
  - [x] ✅ Implement: Change `HttpUrl` to abstract `HttpUrl`
  - [x] ✅ Implement: Use HttpRequest.Builder and HttpClient.execute()
  - [x] ✅ Implement: Update probe methods to use HttpRequestBody
  - [ ] 🟥 Test: Fix failing tests
  - [x] ✅ Update PLAN.md

### Task 5.8: Update BackendApiFactory

- [x] ✅ **Refactor BackendApiFactory to use HttpUrl** (Commit: 92eef03f52)
  - [x] ✅ Implement: Change `okhttp3.HttpUrl` to abstract `HttpUrl` throughout
  - [x] ✅ Implement: Use HttpUrl.parse() instead of HttpUrl.get()
  - [ ] 🟥 Test: Fix failing tests
  - [x] ✅ Update PLAN.md

### Task 5.9: Make OkHttpUtils generic and rename to HttpUtils

**CRITICAL**: OkHttpUtils currently creates okhttp3 objects internally and then wraps them,
which means it will ALWAYS use OkHttp even when JDK HttpClient is available. This task makes
the implementation truly generic by using the factory pattern for dynamic client selection.

- [x] ✅ **Refactor OkHttpUtils to use factories internally** (Commits: ca9a49987e, e5af8aa633)
  - [x] ✅ Implement: Replace `new okhttp3.OkHttpClient.Builder()` with `HttpClient.newBuilder()`
  - [x] ✅ Implement: Replace `new Request.Builder()` with `HttpRequest.newBuilder()` (already done in Task 5.2)
  - [x] ✅ Implement: Refactored buildHttpClient to use HttpClient.Builder API methods
  - [x] ✅ Implement: Updated request body methods to use HttpRequestBody factory methods:
    - msgpackRequestBodyOf → HttpRequestBody.msgpack()
    - gzippedMsgpackRequestBodyOf → HttpRequestBody.gzip(HttpRequestBody.msgpack())
    - gzippedRequestBodyOf → HttpRequestBody.gzip()
    - jsonRequestBodyOf → HttpRequestBody.of()
  - [x] ✅ Implement: Removed all OkHttp-specific private inner classes (JsonRequestBody, ByteBufferRequestBody, etc.)
  - [x] ✅ Implement: Created HttpUtils.java with generic implementation
  - [x] ✅ Implement: Made OkHttpUtils a deprecated delegating wrapper
  - [x] ✅ Implement: Updated all imports and references throughout codebase (main and test)
  - [x] ✅ Test: Compilation succeeds ✓
  - [x] ✅ Test: Tests run (210 passing, 33 failing - same as before)
  - [x] ✅ Update PLAN.md

---

## Phase 6: Update Dependent Modules

### Task 6.1: Update remote-config-core module

- [ ] 🟥 **Remove okhttp dependency from remote-config-core**
  - [ ] 🟥 Write test: Test DefaultConfigurationPoller uses HttpClient
  - [ ] 🟥 Implement: Update `DefaultConfigurationPoller` constructor
    - [ ] 🟥 Change parameter from `OkHttpClient` to `HttpClient`
  - [ ] 🟥 Write test: Test PollerRequestFactory uses HttpRequest
  - [ ] 🟥 Implement: Update `PollerRequestFactory`
    - [ ] 🟥 Replace `okhttp3.HttpUrl` with abstract `HttpUrl`
    - [ ] 🟥 Replace `okhttp3.Request` with abstract `HttpRequest`
    - [ ] 🟥 Replace `okhttp3.RequestBody` with `HttpRequestBody`
    - [ ] 🟥 Replace `okhttp3.MediaType` with content-type header
  - [ ] 🟥 Implement: Update remote-config-core/build.gradle.kts
    - [ ] 🟥 Remove `implementation(libs.okhttp)`
    - [ ] 🟥 Add `api(project(":communication"))` if not present
  - [ ] 🟥 Test: Run `./gradlew :remote-config:remote-config-core:test`
  - [ ] 🟥 Update PLAN.md

### Task 6.2: Update utils/flare-utils module

- [ ] 🟥 **Remove okhttp dependency from flare-utils**
  - [ ] 🟥 Write test: Test TracerFlareService uses HttpClient
  - [ ] 🟥 Implement: Update `TracerFlareService` constructor
    - [ ] 🟥 Change parameter from `OkHttpClient` to `HttpClient`
    - [ ] 🟥 Replace `okhttp3.HttpUrl` with abstract `HttpUrl`
  - [ ] 🟥 Implement: Update sendFlare() method
    - [ ] 🟥 Replace `okhttp3.Request` with `HttpRequest`
    - [ ] 🟥 Replace `okhttp3.RequestBody` with `HttpRequestBody`
    - [ ] 🟥 Replace `okhttp3.MultipartBody` with `HttpRequestBody.multipart()`
    - [ ] 🟥 Replace `okhttp3.MediaType` with content-type header
  - [ ] 🟥 Implement: Update flare-utils/build.gradle.kts
    - [ ] 🟥 Remove `api(libs.okhttp)`
    - [ ] 🟥 Change `compileOnly(project(":communication"))` to `api(project(":communication"))`
  - [ ] 🟥 Test: Run `./gradlew :utils:flare-utils:test`
  - [ ] 🟥 Update PLAN.md

### Task 6.3: Update products/feature-flagging module

- [ ] 🟥 **Remove okhttp dependency from feature-flagging**
  - [ ] 🟥 Write test: Test ExposureWriterImpl uses HttpRequestBody
  - [ ] 🟥 Implement: Update `ExposureWriterImpl.ExposureSerializingHandler`
    - [ ] 🟥 Replace `okhttp3.RequestBody.create()` with `HttpRequestBody.json()`
    - [ ] 🟥 Remove `okhttp3.MediaType` usage
  - [ ] 🟥 Implement: Update feature-flagging/lib/build.gradle.kts
    - [ ] 🟥 Remove dependency on okhttp if present
  - [ ] 🟥 Test: Run `./gradlew :products:feature-flagging:lib:test`
  - [ ] 🟥 Update PLAN.md

### Task 6.4: Update telemetry module

- [ ] 🟥 **Update telemetry module to use HttpClient**
  - [ ] 🟥 Write test: Test telemetry uses HttpClient abstraction
  - [ ] 🟥 Implement: Update telemetry module code to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :telemetry:test`
  - [ ] 🟥 Update PLAN.md

### Task 6.5: Update dd-trace-core module

- [ ] 🟥 **Update dd-trace-core to use HttpClient**
  - [ ] 🟥 Write test: Test dd-trace-core uses HttpClient abstraction
  - [ ] 🟥 Implement: Update any direct OkHttp usage to use abstractions
  - [ ] 🟥 Test: Run `./gradlew :dd-trace-core:test`
  - [ ] 🟥 Update PLAN.md

### Task 6.6: Update dd-java-agent modules

- [ ] 🟥 **Update agent-llmobs module**
  - [ ] 🟥 Write test: Test agent-llmobs uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-llmobs:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update agent-logs-intake module**
  - [ ] 🟥 Write test: Test agent-logs-intake uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-logs-intake:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update agent-debugger module**
  - [ ] 🟥 Write test: Test agent-debugger uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-debugger:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update agent-aiguard module**
  - [ ] 🟥 Write test: Test agent-aiguard uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-aiguard:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update appsec module**
  - [ ] 🟥 Write test: Test appsec uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:appsec:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update agent-crashtracking module**
  - [ ] 🟥 Write test: Test agent-crashtracking uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-crashtracking:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update profiling-uploader module**
  - [ ] 🟥 Write test: Test profiling-uploader uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-profiling:profiling-uploader:test`
  - [ ] 🟥 Update PLAN.md

- [ ] 🟥 **Update agent-ci-visibility module**
  - [ ] 🟥 Write test: Test agent-ci-visibility uses HttpClient abstraction
  - [ ] 🟥 Implement: Update to use HttpClient
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-ci-visibility:test`
  - [ ] 🟥 Update PLAN.md

---

## Phase 7: Integration Testing & Verification

### Task 7.1: Cross-implementation test suite

- [ ] 🟥 **Create unified test suite for both implementations**
  - [ ] 🟥 Write test: Parameterized tests that run against both OkHttp and JDK implementations
  - [ ] 🟥 Implement: Test suite for basic HTTP operations
  - [ ] 🟥 Implement: Test suite for retry logic
  - [ ] 🟥 Implement: Test suite for timeout handling
  - [ ] 🟥 Implement: Test suite for proxy support
  - [ ] 🟥 Implement: Test suite for UDS/named pipes
  - [ ] 🟥 Implement: Test suite for GZIP compression
  - [ ] 🟥 Test: Run `./gradlew :communication:test`
  - [ ] 🟥 Update PLAN.md

### Task 7.2: Integration tests with MockWebServer

- [ ] 🟥 **Update existing integration tests**
  - [ ] 🟥 Write test: Verify existing integration tests work with abstraction
  - [ ] 🟥 Implement: Update integration tests to use HttpClient abstraction
  - [ ] 🟥 Implement: Ensure tests pass with both implementations
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*Integration*"`
  - [ ] 🟥 Update PLAN.md

### Task 7.3: Full module test suite

- [ ] 🟥 **Run all tests across all updated modules**
  - [ ] 🟥 Test: Run `./gradlew :communication:test`
  - [ ] 🟥 Test: Run `./gradlew :remote-config:remote-config-core:test`
  - [ ] 🟥 Test: Run `./gradlew :utils:flare-utils:test`
  - [ ] 🟥 Test: Run `./gradlew :products:feature-flagging:lib:test`
  - [ ] 🟥 Test: Run `./gradlew :telemetry:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-trace-core:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-llmobs:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-logs-intake:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-debugger:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-aiguard:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:appsec:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-crashtracking:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-profiling:profiling-uploader:test`
  - [ ] 🟥 Test: Run `./gradlew :dd-java-agent:agent-ci-visibility:test`
  - [ ] 🟥 Update PLAN.md

### Task 7.4: Smoke tests on different Java versions

- [ ] 🟥 **Verify behavior on different Java versions**
  - [ ] 🟥 Test: Run full test suite on Java 8 (should use OkHttp)
  - [ ] 🟥 Test: Run full test suite on Java 11 (should use JDK HttpClient)
  - [ ] 🟥 Test: Run full test suite on Java 16 (should use JDK HttpClient with native UDS)
  - [ ] 🟥 Test: Run full test suite on Java 17 (should use JDK HttpClient)
  - [ ] 🟥 Update PLAN.md

### Task 7.5: Performance verification

- [ ] 🟥 **Verify no performance regression**
  - [ ] 🟥 Test: Run existing benchmarks on OkHttp implementation
  - [ ] 🟥 Test: Run existing benchmarks on JDK HttpClient implementation
  - [ ] 🟥 Test: Compare results and verify no significant regression
  - [ ] 🟥 Update PLAN.md

---

## Phase 8: Documentation & Cleanup

### Task 8.1: Update build.gradle files

- [ ] 🟥 **Update dependency declarations**
  - [ ] 🟥 Implement: Verify OkHttp is only in communication module's implementation
  - [ ] 🟥 Implement: Verify no transitive okhttp dependencies leak to consumers
  - [ ] 🟥 Implement: Update dependency exclusions if needed
  - [ ] 🟥 Test: Run `./gradlew dependencies` and verify
  - [ ] 🟥 Update PLAN.md

### Task 8.2: Update code coverage exclusions

- [ ] 🟥 **Update coverage configuration**
  - [ ] 🟥 Implement: Update excludedClassesCoverage in communication/build.gradle.kts
  - [ ] 🟥 Implement: Remove old OkHttpUtils exclusions, add new adapter exclusions if needed
  - [ ] 🟥 Update PLAN.md

### Task 8.3: Final verification

- [ ] 🟥 **Complete final checks**
  - [ ] 🟥 Test: Run `./gradlew clean build`
  - [ ] 🟥 Test: Manually verify no okhttp dependency in non-communication modules' build.gradle files
  - [ ] 🟥 Test: Run `./gradlew dependencies` for sample modules and verify no transitive okhttp
  - [ ] 🟥 Test: Verify all tests pass
  - [ ] 🟥 Update PLAN.md to 100%

---

## Summary

**Estimated Impact:**
- **Files Modified:** ~50-70 files
- **Modules Updated:** 15+ modules
- **New Classes:** ~25-30 new classes (abstractions + implementations)
- **Test Files:** ~30-40 new/updated test files

**Key Risks:**
- Unix domain socket support on Java 11-15 (mitigated with jnr-unixsocket)
- Performance regression (mitigated with benchmarks)
- Behavioral differences between OkHttp and JDK HttpClient (mitigated with extensive testing)
