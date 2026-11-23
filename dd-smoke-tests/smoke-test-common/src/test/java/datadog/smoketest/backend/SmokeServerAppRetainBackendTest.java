package datadog.smoketest.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.smoketest.SmokeServerApp;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Regression test for an owned {@link SmokeServerApp} backend that retains across tests: {@code
 * onBeforeEach} must not clear it, since that would discard the app-startup traces the retention is
 * meant to preserve (a shared backend already honors this through its own extension callback; an
 * owned one must too). Uses a counting backend + the trivial {@code TestServerApp}; no agent, no
 * Docker.
 */
class SmokeServerAppRetainBackendTest {

  // Non-shared (never registered as an extension) backend that retains across tests and counts
  // clear() calls; declared before the app so it is initialized when the app builder captures it.
  private static final CountingBackend BACKEND = new CountingBackend();

  @RegisterExtension
  static final SmokeServerApp app =
      SmokeServerApp.named("retain-server")
          .mainClass("datadog.smoketest.TestServerApp")
          .args("--server.port=${app.httpPort}")
          .backend(BACKEND)
          .noAgent()
          .build();

  @Test
  void ownedRetainingBackendIsNotClearedBeforeTests() {
    // onBeforeEach ran before this test method; because the owned backend retains
    // (clearsBetweenTests() == false), the app must not have cleared it.
    assertEquals(0, BACKEND.clears.get(), "a retaining owned backend must not be cleared per-test");
  }

  /** An owned (non-shared) backend that retains across tests and counts {@link #clear()} calls. */
  private static final class CountingBackend extends AgentBackend {
    final AtomicInteger clears = new AtomicInteger();

    @Override
    public void start() {}

    @Override
    public int port() {
      return 0;
    }

    @Override
    public URI url() {
      return URI.create("http://localhost:0");
    }

    @Override
    public Traces traces() {
      return new Traces(Collections::emptyList);
    }

    @Override
    public Telemetry telemetry() {
      return new Telemetry(Collections::emptyList);
    }

    @Override
    public RemoteConfig remoteConfig() {
      return new RemoteConfig((path, config) -> {}, Collections::emptyList);
    }

    @Override
    public void clear() {
      this.clears.incrementAndGet();
    }

    @Override
    public void close() {}

    @Override
    public boolean clearsBetweenTests() {
      return false; // retain across tests
    }
  }
}
