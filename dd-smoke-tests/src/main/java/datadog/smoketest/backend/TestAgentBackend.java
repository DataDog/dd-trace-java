package datadog.smoketest.backend;

import static datadog.smoketest.backend.AgentBackendMessages.decodeMessages;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link AgentBackend} backed by a <a href="https://github.com/DataDog/dd-apm-test-agent">
 * dd-apm-test-agent</a> — either an already-running external agent ({@link Builder#external(String,
 * int)}, defaulting to the {@code CI_AGENT_HOST} sidecar when that variable is set) or a
 * Testcontainers-managed container ({@link Builder#image(String)}, the local-dev default). It reads
 * received traces from the agent's {@code /test/session/traces} JSON endpoint and decodes them into
 * the shared {@link DecodedTrace} model via {@link Decoder#decodeJson(String)}, so a test body
 * written against the common {@link Traces} surface runs unchanged against this backend or the
 * {@link MockAgentBackend}.
 *
 * <p><strong>Per-test isolation.</strong> A shared external agent serves every test in a job, so
 * traces are scoped by an {@code X-Datadog-Test-Session-Token}: the backend owns a token (see
 * {@link #sessionToken()}), the launched app emits it via {@code dd.test.agent.session.token},
 * {@link #clear()} opens a fresh session with it, and {@link #traces()} reads only that session's
 * traces.
 *
 * <p>Testcontainers is a {@code compileOnly} dependency of the smoke base, so this class only loads
 * when a test actually selects a test-agent backend (mock-only tests stay Testcontainers-free).
 */
public final class TestAgentBackend extends AgentBackend {
  private static final String DEFAULT_CI_IMAGE =
      "registry.ddbuild.io/images/mirror/dd-apm-test-agent/ddapm-test-agent";
  private static final String DEFAULT_PUBLIC_IMAGE =
      "ghcr.io/datadog/dd-apm-test-agent/ddapm-test-agent";
  private static final String DEFAULT_VERSION = "v1.64.1";
  private static final String CUSTOM_IMAGE_REF_PROPERTY = "DATADOG_SMOKETEST_TESTAGENT_IMAGE";

  /** Set by the CI jobs providing a dd-apm-test-agent sidecar (see {@code .gitlab-ci.yml}). */
  private static final String CI_AGENT_HOST_ENV = "CI_AGENT_HOST";

  /** The dd-apm-test-agent trace port inside the container. */
  private static final int AGENT_PORT = 8126;

  /**
   * Trace-invariant checks enabled by default, mirroring {@code TracerConnectionReliabilityTest}.
   */
  private static final List<String> DEFAULT_ENABLED_CHECKS =
      asList("trace_count_header", "meta_tracer_version_header", "trace_content_length");

  private static final MediaType JSON = MediaType.parse("application/json");

  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map<String, Object>> MAP_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(Map.class, String.class, Object.class));
  private static final JsonAdapter<List<Map<String, Object>>> REQUEST_LIST_ADAPTER =
      MOSHI.adapter(
          Types.newParameterizedType(
              List.class, Types.newParameterizedType(Map.class, String.class, Object.class)));

  private final String image;
  private final String externalHost; // null => Testcontainers-managed container
  private final int externalPort;
  private final List<String> enabledChecks;
  private final boolean retainAcrossTests;
  private final String sessionToken;

  private final OkHttpClient client = new OkHttpClient();
  private volatile GenericContainer<?> container;
  private volatile HttpUrl baseUrl;
  // Clean external base URI derived from baseUrl once at start() (see cleanBaseUri).
  private volatile URI baseUri;

  private TestAgentBackend(Builder builder) {
    this.image = builder.image;
    this.externalHost = builder.externalHost;
    this.externalPort = builder.externalPort;
    this.enabledChecks = new ArrayList<>(builder.enabledChecks);
    this.retainAcrossTests = builder.retainAcrossTests;
    this.sessionToken =
        builder.sessionToken != null ? builder.sessionToken : "smoke-" + UUID.randomUUID();
  }

  static Builder builder() {
    return new Builder();
  }

  @Override
  public String sessionToken() {
    return this.sessionToken;
  }

  @Override
  public boolean clearsBetweenTests() {
    return !this.retainAcrossTests;
  }

  @Override
  public void start() {
    if (this.baseUrl != null) {
      return;
    }
    if (this.externalHost != null) {
      this.baseUrl =
          new HttpUrl.Builder()
              .scheme("http")
              .host(this.externalHost)
              .port(this.externalPort)
              .build();
    } else {
      GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse(this.image));
      started.withExposedPorts(AGENT_PORT);
      started.withEnv("ENABLED_CHECKS", join(",", this.enabledChecks));
      started.setWaitStrategy(Wait.forHttp("/test/traces"));
      started.start();
      this.container = started;
      this.baseUrl =
          new HttpUrl.Builder()
              .scheme("http")
              .host(started.getHost())
              .port(started.getMappedPort(AGENT_PORT))
              .build();
    }
    // Normalize the external URI
    this.baseUri = cleanBaseUri(this.baseUrl);
    // Open a fresh session so the very first test method starts clean.
    clear();
  }

  @Override
  public int port() {
    return requireStarted().port();
  }

  @Override
  public URI url() {
    requireStarted();
    return this.baseUri;
  }

  @Override
  public Traces traces() {
    return new Traces(this::fetchTraces);
  }

  @Override
  public Telemetry telemetry() {
    return new Telemetry(this::fetchTelemetry);
  }

  @Override
  public void clear() {
    // Reset before opening the session
    resetRemoteConfig();
    // GET /test/session/start begins (and clears) a session identified by the token. The
    // dd-apm-test-agent session endpoints are GET (verified against v1.44.0: POST returns 405).
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/start")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    execute(request, "start test-agent session");
  }

  @Override
  public void close() {
    GenericContainer<?> running = this.container;
    try {
      // Container backends auto-validate their trace-invariant checks at teardown. External CI
      // agents are validated by the job-final .gitlab/check_test_agent_results.sh instead, so we
      // don't check them here. Run before stopping so the agent is still reachable.
      if (running != null) {
        assertNoInvariantFailures();
      }
    } finally {
      if (running != null) {
        this.container = null;
        running.stop();
      }
      this.baseUrl = null;
      this.baseUri = null;
      // Release the HTTP client's dispatcher threads and pooled connections.
      this.client.dispatcher().executorService().shutdown();
      this.client.connectionPool().evictAll();
    }
  }

  /**
   * Asserts the test agent recorded no trace-invariant check failure ({@code ENABLED_CHECKS}) for
   * this backend's session. Auto-invoked at container teardown; may also be called explicitly
   * against an external agent mid-test.
   *
   * @throws AssertionError If the agent recorded one or more trace-invariant check failures.
   */
  public void assertNoInvariantFailures() {
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/trace_check/failures")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    try (Response response = this.client.newCall(request).execute()) {
      int code = response.code();
      // 200 => all checks passed; 404 => a real agent is running (no checks). Anything else is a
      // recorded failure, whose body describes the failing check(s) (see check_test_agent_results).
      if (code == 200 || code == 404) {
        return;
      }
      String body = response.body() == null ? "" : response.body().string();
      throw new AssertionError(
          "Test-agent trace-invariant checks failed (HTTP " + code + "):\n" + body);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to query trace-invariant checks at " + url, e);
    }
  }

  @Override
  public RemoteConfig remoteConfig() {
    return new RemoteConfig(this::pushRemoteConfig, this::fetchRemoteConfigRequests);
  }

  private List<DecodedTrace> fetchTraces() {
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/traces")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    return Decoder.decodeJson(execute(request, "read test-agent session traces")).getTraces();
  }

  private List<Map<String, Object>> fetchTelemetry() {
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/apmtelemetry")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    return decodeMessages(execute(request, "read test-agent session telemetry"));
  }

  private void pushRemoteConfig(String path, String config) {
    // POST {"path": ..., "msg": <config>} to /test/session/responses/config/path; the agent builds
    // the signed RC envelope from it, so callers don't hand-build it (mirrors the Groovy base's
    // setRemoteConfig).
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/responses/config/path")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    String body =
        "{\"path\":\""
            + path.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\",\"msg\":"
            + config
            + "}";
    Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, body)).build();
    execute(request, "set remote-config response");
  }

  private List<Map<String, Object>> fetchRemoteConfigRequests() {
    // The test agent records every request the tracer made in this session; select the /v0.7/config
    // polls and decode their base64-encoded bodies into JSON maps.
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/requests")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    String json = execute(request, "read test-agent session requests");
    List<Map<String, Object>> requests;
    try {
      requests = REQUEST_LIST_ADAPTER.fromJson(json);
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException("Failed to parse /test/session/requests: " + json, e);
    }
    List<Map<String, Object>> polls = new ArrayList<>();
    if (requests != null) {
      for (Map<String, Object> req : requests) {
        Object rawUrl = req.get("url");
        Object rawBody = req.get("body");
        if (rawUrl instanceof String
            && ((String) rawUrl).contains("/v0.7/config")
            && rawBody instanceof String
            && !((String) rawBody).isEmpty()) {
          String decoded = new String(Base64.getDecoder().decode((String) rawBody), UTF_8);
          try {
            Map<String, Object> poll = MAP_ADAPTER.fromJson(decoded);
            if (poll != null) {
              polls.add(poll);
            }
          } catch (IOException | JsonDataException e) {
            throw new IllegalStateException(
                "Failed to parse remote-config poll body: " + decoded, e);
          }
        }
      }
    }
    return polls;
  }

  private void resetRemoteConfig() {
    // A fresh session does not clear a previously-pushed Remote Config response: it is
    // stored under the (stable) session token. Replace it with an empty payload — the
    // tracer's "no configs" default — so each test method starts with a clean RC slate,
    // matching the per-test trace and telemetry isolation.
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/responses/config")
            .addQueryParameter("test_session_token", this.sessionToken)
            .build();
    Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, "{}")).build();
    execute(request, "reset remote-config response");
  }

  private String execute(Request request, String action) {
    try (Response response = this.client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IllegalStateException(
            "Failed to " + action + ": HTTP " + response.code() + " from " + request.url());
      }
      return response.body() == null ? "" : response.body().string();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to " + action + " at " + request.url(), e);
    }
  }

  /**
   * Removes leading bracket and supports IPv6 address.
   *
   * @param url The OkHttp URL to convert to URI.
   * @return The converted URI.
   */
  private static URI cleanBaseUri(HttpUrl url) {
    String host = url.host();
    if (host.indexOf(':') >= 0 && host.charAt(0) != '[') {
      host = "[" + host + "]";
    }
    return URI.create(url.scheme() + "://" + host + ":" + url.port());
  }

  private HttpUrl requireStarted() {
    HttpUrl url = this.baseUrl;
    if (url == null) {
      throw new IllegalStateException("TestAgentBackend not started — call start() first");
    }
    return url;
  }

  /** Fluent builder; obtain via {@link AgentBackend#testAgentBuilder()}. */
  public static final class Builder {
    private String image;
    private String externalHost;
    private int externalPort;
    private final List<String> enabledChecks;
    private boolean retainAcrossTests;
    private String sessionToken;

    private Builder() {
      String customImageRef = System.getenv(CUSTOM_IMAGE_REF_PROPERTY);
      if (customImageRef == null) {
        // Use defaults image and host settings
        boolean runInCi = System.getenv("CI") != null;
        String ciAgentHost = System.getenv(CI_AGENT_HOST_ENV);
        this.image = (runInCi ? DEFAULT_CI_IMAGE : DEFAULT_PUBLIC_IMAGE) + ":" + DEFAULT_VERSION;
        if (ciAgentHost != null) {
          this.externalHost = ciAgentHost;
        }
      } else {
        this.image = customImageRef;
      }
      this.externalPort = AGENT_PORT;
      this.enabledChecks = new ArrayList<>(DEFAULT_ENABLED_CHECKS);
    }

    /**
     * Uses a Testcontainers-managed container of the given image.
     *
     * @param image The dd-apm-test-agent image reference.
     * @return This builder, for chaining.
     */
    public Builder image(String image) {
      this.image = image;
      this.externalHost = null;
      return this;
    }

    /**
     * Overrides the enabled trace-invariant checks ({@code ENABLED_CHECKS}).
     *
     * @param checks The check names to enable (replacing the defaults).
     * @return This builder, for chaining.
     */
    public Builder enabledChecks(String... checks) {
      this.enabledChecks.clear();
      this.enabledChecks.addAll(asList(checks));
      return this;
    }

    /**
     * Talks to an already-running external agent (e.g. the CI sidecar) instead of a container.
     *
     * @param host The external agent host.
     * @param port The external agent port.
     * @return This builder, for chaining.
     */
    public Builder external(String host, int port) {
      this.externalHost = host;
      this.externalPort = port;
      return this;
    }

    /**
     * Keeps received traces across test methods instead of clearing them before each (see {@link
     * AgentBackend#clearsBetweenTests()}). Use when assertions cover app-startup traces, which a
     * per-method clear would discard.
     *
     * @return This builder, for chaining.
     */
    public Builder retainAcrossTests() {
      this.retainAcrossTests = true;
      return this;
    }

    /**
     * Overrides the auto-generated session token (mainly for deterministic tests).
     *
     * @param token The session token to use.
     * @return This builder, for chaining.
     */
    public Builder sessionToken(String token) {
      this.sessionToken = token;
      return this;
    }

    /**
     * Builds the configured test-agent backend.
     *
     * @return The built backend.
     */
    public TestAgentBackend build() {
      return new TestAgentBackend(this);
    }
  }
}
