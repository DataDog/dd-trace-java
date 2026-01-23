# HTTP Client Abstraction Implementation Plan

**Overall Progress:** `38%`

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
  - [ ] 🟥 Write test: JdkHttpUrl adapter tests (deferred to Phase 4)
  - [ ] 🟥 Implement: `datadog.communication.http.jdk.JdkHttpUrl` (wraps java.net.URI) (deferred to Phase 4)
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

- [ ] 🟥 **Create JdkHttpClientAdapter**
  - [ ] 🟥 Write test: Test execute() uses java.net.http.HttpClient
  - [ ] 🟥 Implement: `datadog.communication.http.jdk.JdkHttpClientAdapter` implements HttpClient
    - [ ] 🟥 Wrap `java.net.http.HttpClient`
    - [ ] 🟥 Implement `execute()` by delegating to JDK HttpClient
    - [ ] 🟥 Convert HttpRequest to java.net.http.HttpRequest
    - [ ] 🟥 Convert java.net.http.HttpResponse to HttpResponse
    - [ ] 🟥 Use BodyHandlers.ofInputStream() for response body
  - [ ] 🟥 Write test: Test retry logic integration
  - [ ] 🟥 Implement: `executeWithRetries()` using HttpRetryPolicy
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*JdkHttpClientAdapter*"`
  - [ ] 🟥 Update PLAN.md

### Task 4.2: Implement JdkHttpClient.Builder

- [ ] 🟥 **Create JdkHttpClientBuilder**
  - [ ] 🟥 Write test: Test builder configuration mapping
  - [ ] 🟥 Implement: `datadog.communication.http.jdk.JdkHttpClientBuilder` implements HttpClient.Builder
    - [ ] 🟥 Delegate to HttpClient.Builder internally
    - [ ] 🟥 Map timeout settings using `.connectTimeout(Duration)`
    - [ ] 🟥 Map proxy settings using `.proxy(ProxySelector)`
    - [ ] 🟥 Map UDS via custom HttpClient.Builder configuration
  - [ ] 🟥 Write test: Test build() returns JdkHttpClientAdapter
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*JdkHttpClientBuilder*"`
  - [ ] 🟥 Update PLAN.md

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

- [ ] 🟥 **Implement JDK BodyPublisher adapters**
  - [ ] 🟥 Write test: Test HttpRequestBody.writeTo() converts to BodyPublisher
  - [ ] 🟥 Implement: Adapter that wraps HttpRequestBody as BodyPublisher
    - [ ] 🟥 Use BodyPublishers.ofInputStream() with supplier
    - [ ] 🟥 Handle streaming and content length
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*JdkBodyPublisherAdapter*"`
  - [ ] 🟥 Update PLAN.md

---

## Phase 5: Update Communication Module Internals

### Task 5.1: Update HttpRetryPolicy

- [ ] 🟥 **Refactor HttpRetryPolicy to use HttpResponse abstraction**
  - [ ] 🟥 Write test: Test HttpRetryPolicy with abstract HttpResponse
  - [ ] 🟥 Implement: Change `shouldRetry(okhttp3.Response)` to `shouldRetry(HttpResponse)`
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*HttpRetryPolicy*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.2: Update OkHttpUtils

- [ ] 🟥 **Refactor OkHttpUtils to use abstractions**
  - [ ] 🟥 Write test: Test buildHttpClient returns HttpClient
  - [ ] 🟥 Implement: Change return type from OkHttpClient to HttpClient
  - [ ] 🟥 Implement: Use HttpClient.newBuilder() instead of OkHttpClient.Builder
  - [ ] 🟥 Write test: Test prepareRequest returns HttpRequest.Builder
  - [ ] 🟥 Implement: Change return type to HttpRequest.Builder
  - [ ] 🟥 Write test: Test request body factory methods return HttpRequestBody
  - [ ] 🟥 Implement: Update msgpackRequestBodyOf(), jsonRequestBodyOf(), etc.
  - [ ] 🟥 Write test: Test sendWithRetries uses HttpClient
  - [ ] 🟥 Implement: Change signature to use HttpClient and HttpRequest
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*OkHttpUtils*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.3: Update SharedCommunicationObjects

- [ ] 🟥 **Refactor SharedCommunicationObjects**
  - [ ] 🟥 Write test: Test agentHttpClient uses HttpClient abstraction
  - [ ] 🟥 Implement: Change `public OkHttpClient agentHttpClient` to `private HttpClient agentHttpClient`
  - [ ] 🟥 Implement: Add `public HttpClient getAgentHttpClient()` getter
  - [ ] 🟥 Write test: Test agentUrl uses HttpUrl abstraction
  - [ ] 🟥 Implement: Change `public HttpUrl agentUrl` to `private HttpUrl agentUrl`
  - [ ] 🟥 Implement: Add `public HttpUrl getAgentUrl()` getter
  - [ ] 🟥 Write test: Test intakeHttpClient uses HttpClient abstraction
  - [ ] 🟥 Implement: Change `private OkHttpClient intakeHttpClient` to `private HttpClient intakeHttpClient`
  - [ ] 🟥 Implement: Update `getIntakeHttpClient()` to return HttpClient
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*SharedCommunicationObjects*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.4: Update BackendApi interface

- [ ] 🟥 **Refactor BackendApi to use abstractions**
  - [ ] 🟥 Write test: Test BackendApi post method with HttpRequestBody
  - [ ] 🟥 Implement: Change `post()` signature:
    - [ ] 🟥 Replace `okhttp3.RequestBody` with `HttpRequestBody`
    - [ ] 🟥 Replace `OkHttpUtils.CustomListener` with `HttpListener`
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*BackendApi*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.5: Update IntakeApi

- [ ] 🟥 **Refactor IntakeApi to use HttpClient**
  - [ ] 🟥 Write test: Test IntakeApi uses HttpClient
  - [ ] 🟥 Implement: Change constructor parameter from `OkHttpClient` to `HttpClient`
  - [ ] 🟥 Implement: Change `HttpUrl` to abstract `HttpUrl`
  - [ ] 🟥 Implement: Use HttpRequest.Builder for request construction
  - [ ] 🟥 Implement: Update post() method to use HttpRequestBody
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*IntakeApi*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.6: Update EvpProxyApi

- [ ] 🟥 **Refactor EvpProxyApi to use HttpClient**
  - [ ] 🟥 Write test: Test EvpProxyApi uses HttpClient
  - [ ] 🟥 Implement: Change constructor parameter from `OkHttpClient` to `HttpClient`
  - [ ] 🟥 Implement: Change `HttpUrl` to abstract `HttpUrl`
  - [ ] 🟥 Implement: Use HttpRequest.Builder for request construction
  - [ ] 🟥 Implement: Update post() method to use HttpRequestBody
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*EvpProxyApi*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.7: Update DDAgentFeaturesDiscovery

- [ ] 🟥 **Refactor DDAgentFeaturesDiscovery to use HttpClient**
  - [ ] 🟥 Write test: Test DDAgentFeaturesDiscovery uses HttpClient
  - [ ] 🟥 Implement: Change constructor parameter from `OkHttpClient` to `HttpClient`
  - [ ] 🟥 Implement: Change `HttpUrl` to abstract `HttpUrl`
  - [ ] 🟥 Implement: Use HttpRequest.Builder for request construction
  - [ ] 🟥 Implement: Update probe methods to use HttpRequestBody
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*DDAgentFeaturesDiscovery*"`
  - [ ] 🟥 Update PLAN.md

### Task 5.8: Update BackendApiFactory

- [ ] 🟥 **Refactor BackendApiFactory to use HttpUrl**
  - [ ] 🟥 Write test: Test BackendApiFactory uses abstract HttpUrl
  - [ ] 🟥 Implement: Change `okhttp3.HttpUrl` to abstract `HttpUrl` throughout
  - [ ] 🟥 Test: Run `./gradlew :communication:test --tests "*BackendApiFactory*"`
  - [ ] 🟥 Update PLAN.md

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
