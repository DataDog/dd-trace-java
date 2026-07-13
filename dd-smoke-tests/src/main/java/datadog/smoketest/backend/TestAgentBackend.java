package datadog.smoketest.backend;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link TraceBackend} backed by a <a href="https://github.com/DataDog/dd-apm-test-agent">
 * dd-apm-test-agent</a> — either a Testcontainers-managed container ({@link Builder#build()
 * .container()}, the local-dev default) or an already-running external agent ({@link
 * Builder#external(String, int)}, the CI sidecar). It reads received traces from the agent's {@code
 * /test/session/traces} JSON endpoint and decodes them into the shared {@link DecodedTrace} model
 * via {@link TestAgentTraceDecoder} (S1b), so a test body written against the common {@link Traces}
 * surface runs unchanged against this backend or the {@link MockAgentBackend} (Q2).
 *
 * <p><strong>Per-test isolation (Q4a, Option B).</strong> A shared external agent serves every test
 * in a job, so traces are scoped by an {@code X-Datadog-Test-Session-Token}: the backend owns a
 * token (see {@link #sessionToken()}), the launched app emits it via {@code
 * dd.trace.agent.test.session.token} (S3a tracer change, wired by the S4 app launcher), {@link
 * #clear()} opens a fresh session with it, and {@link #traces()} reads only that session's traces.
 *
 * <p>Testcontainers is a {@code compileOnly} dependency of the smoke base, so this class only loads
 * when a test actually selects a test-agent backend (mock-only tests stay Testcontainers-free).
 */
public final class TestAgentBackend implements TraceBackend {
  /** The dd-apm-test-agent trace port inside the container. */
  private static final int AGENT_PORT = 8126;

  /**
   * Default image — the CI mirror used by {@code TracerConnectionReliabilityTest}. Override with
   * {@link Builder#image(String)} or the {@code datadog.smoketest.testagent.image} system property
   * (e.g. the public {@code ghcr.io/datadog/dd-apm-test-agent/ddapm-test-agent} for local runs
   * without registry access).
   */
  private static final String DEFAULT_IMAGE =
      System.getProperty(
          "datadog.smoketest.testagent.image",
          "registry.ddbuild.io/images/mirror/dd-apm-test-agent/ddapm-test-agent:v1.44.0");

  /**
   * Trace-invariant checks enabled by default, mirroring {@code TracerConnectionReliabilityTest}.
   */
  private static final List<String> DEFAULT_ENABLED_CHECKS =
      Arrays.asList("trace_count_header", "meta_tracer_version_header", "trace_content_length");

  private final String image;
  private final String externalHost; // null => Testcontainers-managed container
  private final int externalPort;
  private final List<String> enabledChecks;
  private final boolean shared;
  private final String sessionToken;

  private final OkHttpClient client = new OkHttpClient();
  private volatile GenericContainer<?> container;
  private volatile HttpUrl baseUrl;

  private TestAgentBackend(Builder builder) {
    this.image = builder.image;
    this.externalHost = builder.externalHost;
    this.externalPort = builder.externalPort;
    this.enabledChecks = new ArrayList<>(builder.enabledChecks);
    this.shared = builder.shared;
    this.sessionToken =
        builder.sessionToken != null ? builder.sessionToken : "smoke-" + UUID.randomUUID();
  }

  static Builder builder() {
    return new Builder();
  }

  /**
   * The session token this backend scopes its traces by. The launched app must emit it via {@code
   * -Ddd.trace.agent.test.session.token=<token>} so its traces land in this backend's session.
   */
  @Override
  public String sessionToken() {
    return sessionToken;
  }

  /** Whether this backend is meant to be shared across multiple apps (consumed by S6). */
  @Override
  public boolean isShared() {
    return shared;
  }

  @Override
  public void start() {
    if (baseUrl != null) {
      return;
    }
    if (externalHost != null) {
      baseUrl = new HttpUrl.Builder().scheme("http").host(externalHost).port(externalPort).build();
    } else {
      GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse(image));
      started.withExposedPorts(AGENT_PORT);
      started.withEnv("ENABLED_CHECKS", String.join(",", enabledChecks));
      started.setWaitStrategy(Wait.forHttp("/test/traces"));
      started.start();
      container = started;
      baseUrl =
          new HttpUrl.Builder()
              .scheme("http")
              .host(started.getHost())
              .port(started.getMappedPort(AGENT_PORT))
              .build();
    }
    // Open a fresh session so the very first test method starts clean.
    clear();
  }

  @Override
  public int port() {
    return requireStarted().port();
  }

  @Override
  public URI url() {
    HttpUrl url = requireStarted();
    // Clean base URI without HttpUrl's trailing "/", so callers can append their own path.
    return URI.create(url.scheme() + "://" + url.host() + ":" + url.port());
  }

  @Override
  public Traces traces() {
    return new Traces(this::fetchTraces);
  }

  @Override
  public void clear() {
    // GET /test/session/start begins (and clears) a session identified by the token. The
    // dd-apm-test-agent session endpoints are GET (verified against v1.44.0: POST returns 405).
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/start")
            .addQueryParameter("test_session_token", sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    execute(request, "start test-agent session");
  }

  @Override
  public void close() {
    GenericContainer<?> running = container;
    if (running != null) {
      container = null;
      running.stop();
    }
    baseUrl = null;
    // TODO S5: on teardown of a container backend, query /test/trace_check/failures and fail the
    //  class if any enabled trace-invariant check failed (Q5). External CI agents are validated by
    //  the job-final .gitlab/check_test_agent_results.sh instead.
  }

  private List<DecodedTrace> fetchTraces() {
    HttpUrl url =
        requireStarted()
            .newBuilder()
            .addPathSegments("test/session/traces")
            .addQueryParameter("test_session_token", sessionToken)
            .build();
    Request request = new Request.Builder().url(url).get().build();
    return TestAgentTraceDecoder.decode(execute(request, "read test-agent session traces"));
  }

  private String execute(Request request, String action) {
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IllegalStateException(
            "Failed to " + action + ": HTTP " + response.code() + " from " + request.url());
      }
      return response.body() == null ? "" : response.body().string();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to " + action + " at " + request.url(), e);
    }
  }

  private HttpUrl requireStarted() {
    HttpUrl url = baseUrl;
    if (url == null) {
      throw new IllegalStateException("TestAgentBackend not started — call start() first");
    }
    return url;
  }

  /** Fluent builder for a {@link TestAgentBackend}; obtain via {@code TraceBackend.testAgent()}. */
  public static final class Builder {
    private String image = DEFAULT_IMAGE;
    private String externalHost;
    private int externalPort = AGENT_PORT;
    private final List<String> enabledChecks = new ArrayList<>(DEFAULT_ENABLED_CHECKS);
    private boolean shared;
    private String sessionToken;

    private Builder() {}

    /**
     * Uses a Testcontainers-managed container of the given image (default {@link #DEFAULT_IMAGE}).
     */
    public Builder image(String image) {
      this.image = image;
      return this;
    }

    /** Overrides the enabled trace-invariant checks ({@code ENABLED_CHECKS}). */
    public Builder enabledChecks(String... checks) {
      this.enabledChecks.clear();
      this.enabledChecks.addAll(Arrays.asList(checks));
      return this;
    }

    /** Talks to an already-running external agent (e.g. the CI sidecar) instead of a container. */
    public Builder external(String host, int port) {
      this.externalHost = host;
      this.externalPort = port;
      return this;
    }

    /** Marks this backend as shared across multiple apps (multi-app wiring lands in S6). */
    public Builder shared() {
      this.shared = true;
      return this;
    }

    /** Overrides the auto-generated session token (mainly for deterministic tests). */
    public Builder sessionToken(String token) {
      this.sessionToken = token;
      return this;
    }

    public TestAgentBackend build() {
      return new TestAgentBackend(this);
    }
  }
}
