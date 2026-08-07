package datadog.smoketest.backend;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.net.URI;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A pluggable trace backend a smoke-test app sends its traces to. Two implementations are provided:
 * the in-process {@link #mockAgent() mock agent} and a Dockerized or external {@link #testAgent()
 * dd-apm-test-agent}. Both decode received traces into the shared {@link DecodedTrace} model, so a
 * test body written against the common {@link Traces} surface runs unchanged on either backend.
 *
 * <p>The lifecycle mirrors the JUnit extension that owns the backend: {@link #start()} once per
 * test class, {@link #clear()} between methods, {@link #close()} at teardown.
 */
public abstract class AgentBackend
    implements AutoCloseable, BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
  private volatile boolean registered;

  /** Starts the backend and binds it to a port. Idempotent. */
  public abstract void start();

  /**
   * Returns the agent port the app should send traces to (e.g. {@code -Ddd.trace.agent.port}).
   *
   * @return The bound agent port.
   */
  public abstract int port();

  /**
   * Returns the base URL of the backend.
   *
   * @return The backend base URL.
   */
  public abstract URI url();

  /**
   * Returns the query/assert facade over the traces this backend has received.
   *
   * @return The {@link Traces} facade for this backend.
   */
  public abstract Traces traces();

  /**
   * Returns the query facade over the app-telemetry messages this backend has received.
   *
   * @return The {@link Telemetry} facade for this backend.
   */
  public abstract Telemetry telemetry();

  /**
   * Returns the Remote Configuration facade of this backend: push a config the app's tracer will
   * receive on its next {@code /v0.7/config} poll, and read back the tracer's poll requests.
   *
   * @return The {@link RemoteConfig} facade for this backend.
   */
  public abstract RemoteConfig remoteConfig();

  /** Discards all traces received so far — call between test methods to isolate them. */
  public abstract void clear();

  @Override
  public abstract void close();

  /**
   * Returns the session token the launched app must emit (via {@code dd.test.agent.session.token})
   * for its traces to be attributed to this backend. The in-process mock owns its own server and
   * does not scope by session, so it returns {@code null}; the test agent overrides this.
   *
   * @return The session token, or {@code null} if the backend does not scope by session.
   */
  public String sessionToken() {
    return null;
  }

  /**
   * Returns whether this backend manages its own lifecycle as a separate {@code @RegisterExtension}
   * shared across apps. This is inferred from JUnit registration — an inline backend is not a
   * registered extension, so it returns {@code false} — and is therefore only accurate once the
   * extension lifecycle has begun (from {@link #beforeAll} onward). When {@code false}, the owning
   * app starts and stops the backend.
   *
   * @return {@code true} if the backend is shared and drives its own lifecycle.
   */
  public final boolean isShared() {
    return this.registered;
  }

  /**
   * Returns whether {@link #beforeEach} clears received traces so each test method sees only its
   * own (the default). Return {@code false} to <em>accumulate</em> across methods — needed when the
   * assertions cover traces emitted at app startup (before the first test method), which a
   * per-method clear would discard.
   *
   * @return {@code true} to clear between methods, {@code false} to accumulate across them.
   */
  public boolean clearsBetweenTests() {
    return true;
  }

  @Override
  public final void beforeAll(ExtensionContext context) {
    this.registered = true;
    start();
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    if (clearsBetweenTests()) {
      clear();
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    close();
  }

  /**
   * Creates an in-process mock-agent backend wrapping the testing {@code JavaTestHttpServer}.
   *
   * @return A new in-process mock-agent backend.
   */
  public static AgentBackend mockAgent() {
    return new MockAgentBackend();
  }

  /**
   * Starts a fluent builder for a {@link TestAgentBackend} (dd-apm-test-agent container or
   * external).
   *
   * @return A new test-agent backend builder.
   */
  public static TestAgentBackend.Builder testAgentBuilder() {
    return TestAgentBackend.builder();
  }

  /**
   * Resolves the environment's default test-agent backend: the external CI sidecar when {@code
   * CI_AGENT_HOST} is set, otherwise a Testcontainers-managed container (which requires a running
   * Docker daemon).
   *
   * @return An external or containerized test-agent backend.
   */
  public static AgentBackend testAgent() {
    return testAgentBuilder().build();
  }
}
