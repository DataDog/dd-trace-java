package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.MockAgentBackend;
import datadog.smoketest.backend.TraceBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Multi-app composition with a shared backend (Q8): a single {@code @RegisterExtension} backend
 * declared before two {@link SmokeApp} fields, each launching its own JVM. The shared backend is
 * started/reset/closed by <em>its own</em> extension — the apps reference it (via field access) but
 * don't own its lifecycle. Start-up is order-independent because {@code SmokeApp} starts the
 * backend idempotently.
 *
 * <p>Runs without the agent, so it asserts the composition wiring (distinct app ports, one shared
 * backend instance) rather than trace flow — cross-app trace assertions against a shared agent land
 * in the S8 pilot.
 */
class SharedBackendMultiAppTest {

  @RegisterExtension static final MockAgentBackend agent = TraceBackend.mockAgent().shared();

  @RegisterExtension
  static final SmokeApp producer =
      SmokeApp.named("producer")
          .mainClass("datadog.smoketest.TestServerApp")
          .args("--server.port=${app.httpPort}")
          .backend(agent)
          .noAgent()
          .build();

  @RegisterExtension
  static final SmokeApp consumer =
      SmokeApp.named("consumer")
          .mainClass("datadog.smoketest.TestServerApp")
          .args("--server.port=${app.httpPort}")
          .backend(agent)
          .noAgent()
          .build();

  @Test
  void bothAppsRunOnDistinctPorts() {
    assertNotEquals(producer.httpPort(), consumer.httpPort(), "each app gets its own port");
    assertEquals(200, producer.get("/"), "producer serves HTTP");
    assertEquals(200, consumer.get("/"), "consumer serves HTTP");
  }

  @Test
  void appsShareTheSameBackend() {
    assertTrue(agent.isShared(), "backend is marked shared");
    assertSame(agent, producer.backend(), "producer uses the shared backend");
    assertSame(agent, consumer.backend(), "consumer uses the shared backend");
    assertEquals(
        producer.backend().port(),
        consumer.backend().port(),
        "one shared backend => one agent port for both apps");
  }

  @Test
  void sharedBackendIsStartedByItsOwnExtension() {
    assertNotNull(agent.url(), "shared backend was started");
    assertTrue(agent.traces().getTraces().isEmpty(), "no traces arrive without an agent");
  }
}
