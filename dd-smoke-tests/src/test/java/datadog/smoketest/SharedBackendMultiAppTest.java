package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Multi-app composition with a shared backend: a single {@code @RegisterExtension} backend declared
 * before two {@link SmokeServerApp} fields, each launching its own JVM. The shared backend is
 * started/reset/closed by <em>its own</em> extension — the apps reference it (via field access) but
 * don't own its lifecycle. Start-up is order-independent because {@code SmokeServerApp} starts the
 * backend idempotently; {@code @Order} makes the backend tear down <em>after</em> the apps (JUnit
 * runs teardown in reverse registration order, and {@code @RegisterExtension} field order is not
 * otherwise guaranteed), so the backend stays up while the child JVMs shut down.
 *
 * <p>Runs without the agent, so it asserts the composition wiring (distinct app ports, one shared
 * backend instance) rather than trace flow — cross-app trace assertions against a shared agent are
 * exercised by the Spring Boot RabbitMQ pilot.
 */
class SharedBackendMultiAppTest {

  @Order(1)
  @RegisterExtension
  static final AgentBackend agent = AgentBackend.mockAgent();

  @Order(2)
  @RegisterExtension
  static final SmokeServerApp producer =
      SmokeServerApp.named("producer")
          .mainClass("datadog.smoketest.TestServerApp")
          .args("--server.port=${app.httpPort}")
          .backend(agent)
          .noAgent()
          .build();

  @Order(3)
  @RegisterExtension
  static final SmokeServerApp consumer =
      SmokeServerApp.named("consumer")
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
    assertTrue(agent.isShared(), "backend is inferred shared from its extension registration");
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
