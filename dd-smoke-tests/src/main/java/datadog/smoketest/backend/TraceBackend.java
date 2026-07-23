package datadog.smoketest.backend;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.net.URI;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;

/**
 * A pluggable trace backend a smoke-test app sends its traces to. Two implementations are planned:
 * the in-process {@link #mockAgent() mock agent} (this step, S2) and a Dockerized/external
 * dd-apm-test-agent (S3). Both decode received traces into the shared {@link DecodedTrace} model
 * (via the msgpack {@code Decoder}, or {@link TestAgentTraceDecoder} for the test agent), so a test
 * body written against the common {@link Traces} surface runs unchanged on either backend (Q2).
 *
 * <p>The lifecycle mirrors the JUnit extension that will own the backend (S4/S6): {@link #start()}
 * once per test class, {@link #clear()} between methods, {@link #close()} at teardown.
 */
public interface TraceBackend
    extends AutoCloseable, BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
  /** Starts the backend and binds it to a port. Idempotent. */
  void start();

  /** The agent port the app should send traces to (e.g. {@code -Ddd.trace.agent.port}). */
  int port();

  /** The base URL of the backend. */
  URI url();

  /** Query/assert facade over the traces this backend has received. */
  Traces traces();

  /** Query facade over the app-telemetry messages this backend has received (S9). */
  Telemetry telemetry();

  /** Discards all traces received so far — call between test methods to isolate them. */
  void clear();

  @Override
  void close();

  /**
   * The session token the launched app must emit (via {@code dd.test.agent.session.token}) for its
   * traces to be attributed to this backend, or {@code null} if the backend does not scope by
   * session (e.g. the in-process mock, which owns its own server). Overridden by the test agent.
   */
  default String sessionToken() {
    return null;
  }

  /**
   * Whether this backend manages its own lifecycle as a separate {@code @RegisterExtension} shared
   * across apps (S6). When {@code false} (default), the owning {@code AbstractSmokeApp}
   * starts/stops it.
   */
  default boolean isShared() {
    return false;
  }

  /**
   * Whether {@link #beforeEach} clears received traces so each test method sees only its own (the
   * default). Return {@code false} to <em>accumulate</em> across methods — needed when the
   * assertions cover traces emitted at app startup (before the first test method), which a
   * per-method clear would discard. Overridden by the test agent's {@code retainAcrossTests()}.
   */
  default boolean clearsBetweenTests() {
    return true;
  }

  // JUnit lifecycle: a backend declared as its own `@RegisterExtension` field (shared across apps,
  // S6/Q8) drives its own start/clear/close. An inline backend passed to `backend(...)` on an app
  // builder is not registered as an extension, so the app drives it instead. start() is idempotent,
  // so a shared backend that an app also (defensively) starts is unaffected.

  @Override
  default void beforeAll(ExtensionContext context) {
    start();
  }

  @Override
  default void beforeEach(ExtensionContext context) {
    if (clearsBetweenTests()) {
      clear();
    }
  }

  @Override
  default void afterAll(ExtensionContext context) {
    close();
  }

  /** Creates an in-process mock-agent backend wrapping the testing {@code JavaTestHttpServer}. */
  static MockAgentBackend mockAgent() {
    return new MockAgentBackend();
  }

  /** Fluent builder for a {@link TestAgentBackend} (dd-apm-test-agent container or external). */
  static TestAgentBackend.Builder testAgentBuilder() {
    return TestAgentBackend.builder();
  }

  /**
   * Resolves the environment's default test-agent backend (Q4): the external CI sidecar when {@code
   * CI_AGENT_HOST} is set, else a Testcontainers-managed container when Docker is available, else a
   * loud skip (a {@link TestAbortedException} marks the test aborted rather than failed, so a dev
   * not running smoke tests isn't blocked). The mock backend is never an automatic fallback —
   * select it explicitly with {@link #mockAgent()}. The reusable conditional-execution gate over
   * this policy is {@link EnabledIfDockerAvailable} (S7).
   */
  static TraceBackend testAgent() {
    String ciAgentHost = System.getenv("CI_AGENT_HOST");
    if (ciAgentHost != null && !ciAgentHost.isEmpty()) {
      return testAgentBuilder().external(ciAgentHost, 8126).build();
    }
    if (DockerClientFactory.instance().isDockerAvailable()) {
      return testAgentBuilder().build();
    }
    throw new TestAbortedException(
        "No test-agent backend available: set CI_AGENT_HOST for an external agent, or start Docker "
            + "for a container. Use TraceBackend.mockAgent() to opt into the in-process mock.");
  }
}
