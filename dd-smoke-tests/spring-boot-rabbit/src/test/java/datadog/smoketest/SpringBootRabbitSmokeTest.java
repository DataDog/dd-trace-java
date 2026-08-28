package datadog.smoketest;

import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.SORT_BY_ANCESTRY;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.smoketest.backend.AgentBackend;
import datadog.smoketest.backend.TestAgentBackend;
import datadog.smoketest.backend.Traces;
import datadog.smoketest.trace.SpanMatcher;
import datadog.smoketest.trace.TraceMatcher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Two Spring Boot apps — a sender and a receiver — round-trip a message through RabbitMQ, and both
 * report to a single <em>shared</em> test-agent backend, so one {@code @RegisterExtension} agent
 * captures the distributed trace spanning both JVMs and the broker.
 *
 * <p>With {@code dd.rabbit.legacy.tracing.enabled=false} the whole round-trip is a single
 * context-propagated trace — a strict parent→child chain of 12 spans, rooted at the sender {@code
 * servlet.request}:
 *
 * <pre>
 * spring-rabbit-0 servlet.request GET /roundtrip/{message}
 *   spring-rabbit-0 spring.handler WebController.roundtrip
 *     spring-rabbit-0 amqp.command  basic.publish -> otherqueue     (send)
 *       rabbitmq        amqp.deliver amqp.deliver otherqueue
 *         spring-rabbit-1 amqp.command basic.deliver otherqueue
 *           spring-rabbit-1 amqp.consume amqp.consume otherqueue
 *             spring-rabbit-1 spring.consume Receiver.receiveMessage  (receiver consumes)
 *               spring-rabbit-1 amqp.command basic.publish -> queue    (receiver forwards reply)
 *                 rabbitmq        amqp.deliver amqp.deliver queue
 *                   spring-rabbit-0 amqp.command basic.deliver queue
 *                     spring-rabbit-0 amqp.consume amqp.consume queue
 *                       spring-rabbit-0 spring.consume Receiver.receiveMessage (sender consumes reply)
 * </pre>
 *
 * <p>The whole collection is asserted with {@link Traces#waitForTraces} in order-independent subset
 * mode ({@code unorder().ignoreAdditionalTraces()}): each matcher matches a distinct received trace
 * and extras are ignored. Each round-trip trace is still matched count-exact (all 12 spans, in
 * {@link TraceMatcher#SORT_BY_ANCESTRY ancestry order}), verifying every AMQP operation
 * (publish/deliver/consume, both directions) <em>and</em> its cross-service linkage — while staying
 * robust to the timing-dependent extras: the broker emits its connection-setup commands and per-ack
 * traces as their own single-span traces, in non-deterministic count and order.
 *
 * <p>Two constraints these assertions depend on:
 *
 * <ul>
 *   <li><b>Ancestry order, not start time</b> — the 12-span round-trip is a strict linear chain,
 *       but its spans start within the same tick and race, so {@code SORT_BY_START_TIME} is
 *       unstable across runs. {@code SORT_BY_ANCESTRY} orders each parent before its child
 *       (timestamp-independent along the chain), giving a stable positional order.
 *   <li><b>Accumulate, don't isolate</b> — {@code retainAcrossTests()} keeps traces from app
 *       start-up onward, because {@code basic.qos}/{@code basic.consume}/{@code queue.declare} are
 *       emitted when the consumers start, before any test method, and a per-method session {@code
 *       clear()} would discard them.
 * </ul>
 */
@Testcontainers
class SpringBootRabbitSmokeTest {
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.springboot.shadowJar.path");
  private static final int TIMEOUT_SECONDS = 60;
  private static final int RABBIT_AMQP_PORT = 5672;
  private static final OkHttpClient CLIENT = new OkHttpClient();
  // AMQP connection-setup / ack commands each app emits as its own (single-span) trace.
  private static final String[] ADMIN_COMMANDS = {
    "basic.qos", "basic.consume", "basic.ack", "queue.declare"
  };

  @Container
  private static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.9.20-alpine"));

  @Order(1)
  @RegisterExtension
  static final TestAgentBackend agent = AgentBackend.testAgentBuilder().retainAcrossTests().build();

  @Order(2)
  @RegisterExtension
  static final SmokeServerApp sender =
      rabbitApp(0).args("--rabbit.sender.queue=otherqueue").build();

  @Order(3)
  @RegisterExtension
  static final SmokeServerApp receiver =
      rabbitApp(1)
          .args("--rabbit.receiver.queue=otherqueue", "--rabbit.receiver.forward=true")
          .build();

  @Test
  void roundTripsProduceFullAmqpTraceStructure() throws IOException {
    // Drive 3 round-trips through the sender; each travels
    // sender -> otherqueue -> receiver -> queue -> sender.
    String[] messages = {"foo", "bar", "baz"};
    for (String message : messages) {
      Request request =
          new Request.Builder().url(sender.url() + "/roundtrip/" + message).get().build();
      try (Response response = CLIENT.newCall(request).execute()) {
        assertEquals(200, response.code(), "round-trip " + message);
        ResponseBody body = response.body();
        assertNotNull(body, "round-trip " + message + " response body is null");
        assertEquals("Got: >" + message, body.string(), "round-trip " + message);
      }
    }

    // One full round-trip trace per message, plus each service's connection-setup/ack commands.
    List<TraceMatcher> expected = new ArrayList<>();
    for (int i = 0; i < messages.length; i++) {
      expected.add(roundTrip());
    }
    for (String service : new String[] {"spring-rabbit-0", "spring-rabbit-1"}) {
      for (String command : ADMIN_COMMANDS) {
        expected.add(admin(service, command));
      }
    }
    agent
        .traces()
        .waitForTraces(
            TIMEOUT_SECONDS,
            o -> o.unorder().ignoreAdditionalTraces(),
            expected.toArray(new TraceMatcher[0]));
  }

  // The full distributed round-trip: HTTP entrypoint -> publish -> receiver consumes and forwards
  // -> sender consumes the reply. Each matcher after the root pins its parent to the preceding span
  // with childOfPrevious(), so the chain asserts the cross-service linkage, not just the shape.
  private static TraceMatcher roundTrip() {
    return trace(
        SORT_BY_ANCESTRY,
        sp("spring-rabbit-0", "servlet.request", "GET /roundtrip/{message}").root(),
        sp("spring-rabbit-0", "spring.handler", "WebController.roundtrip").childOfPrevious(),
        sp("spring-rabbit-0", "amqp.command", "basic.publish <default> -> otherqueue")
            .childOfPrevious(),
        sp("rabbitmq", "amqp.deliver", "amqp.deliver otherqueue").childOfPrevious(),
        sp("spring-rabbit-1", "amqp.command", "basic.deliver otherqueue").childOfPrevious(),
        sp("spring-rabbit-1", "amqp.consume", "amqp.consume otherqueue").childOfPrevious(),
        sp("spring-rabbit-1", "spring.consume", "Receiver.receiveMessage").childOfPrevious(),
        sp("spring-rabbit-1", "amqp.command", "basic.publish <default> -> queue").childOfPrevious(),
        sp("rabbitmq", "amqp.deliver", "amqp.deliver queue").childOfPrevious(),
        sp("spring-rabbit-0", "amqp.command", "basic.deliver queue").childOfPrevious(),
        sp("spring-rabbit-0", "amqp.consume", "amqp.consume queue").childOfPrevious(),
        sp("spring-rabbit-0", "spring.consume", "Receiver.receiveMessage").childOfPrevious());
  }

  // A connection-setup / ack command emitted as its own single-span (root) trace.
  private static TraceMatcher admin(String service, String command) {
    return trace(sp(service, "amqp.command", command).root());
  }

  private static SpanMatcher sp(String service, String operation, String resource) {
    SpanMatcher matcher = span().service(service).operationName(operation).resourceName(resource);
    String type = spanType(operation);
    if (type != null) {
      matcher.type(type);
    }
    return matcher;
  }

  private static String spanType(String operation) {
    switch (operation) {
      case "servlet.request":
      case "spring.handler":
        return "web";
      case "amqp.command":
      case "amqp.deliver":
      case "spring.consume":
        return "queue";
      case "amqp.consume":
      default:
        return null;
    }
  }

  private static SmokeServerApp.Builder rabbitApp(int index) {
    return SmokeServerApp.named("spring-rabbit-" + index)
        .jar(APPLICATION_JAR)
        .backend(agent)
        .jvmArgs(
            "-Ddd.service.name=spring-rabbit-" + index, "-Ddd.rabbit.legacy.tracing.enabled=false")
        // Resolved at launch, after @Testcontainers has started RABBIT — not at build time.
        .placeholder("rabbit.host", RABBIT::getHost)
        .placeholder("rabbit.port", () -> String.valueOf(RABBIT.getMappedPort(RABBIT_AMQP_PORT)))
        .args(
            "--server.port=${app.httpPort}",
            "--spring.rabbitmq.host=${rabbit.host}",
            "--spring.rabbitmq.port=${rabbit.port}")
        // The broker connection is torn down noisily when the app is killed at teardown.
        .allowedErrorLogs(
            "Failed to check/redeclare auto-delete queue(s)",
            "An unexpected connection driver error occured");
  }
}
