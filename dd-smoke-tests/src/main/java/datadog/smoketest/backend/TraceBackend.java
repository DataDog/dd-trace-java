package datadog.smoketest.backend;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.net.URI;

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
public interface TraceBackend extends AutoCloseable {
  /** Starts the backend and binds it to a port. Idempotent. */
  void start();

  /** The agent port the app should send traces to (e.g. {@code -Ddd.trace.agent.port}). */
  int port();

  /** The base URL of the backend. */
  URI url();

  /** Query/assert facade over the traces this backend has received. */
  Traces traces();

  /** Discards all traces received so far — call between test methods to isolate them. */
  void clear();

  @Override
  void close();

  /** Creates an in-process mock-agent backend wrapping the testing {@code JavaTestHttpServer}. */
  static MockAgentBackend mockAgent() {
    return new MockAgentBackend();
  }

  // TODO S3: testAgent() — a Testcontainers-managed .container() / external CI agent backend, plus
  //  the test-agent trace-invariant checks (ENABLED_CHECKS) and JSON decoding via
  //  TestAgentTraceDecoder.
}
