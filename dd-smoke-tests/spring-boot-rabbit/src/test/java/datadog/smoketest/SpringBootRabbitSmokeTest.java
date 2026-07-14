package datadog.smoketest;

import static datadog.smoketest.trace.SpanMatcher.span;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.smoketest.backend.EnabledIfDockerAvailable;
import datadog.smoketest.backend.TestAgentBackend;
import datadog.smoketest.backend.TraceBackend;
import datadog.smoketest.backend.Traces;
import datadog.smoketest.trace.SpanMatcher;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S8 multi-app pilot — ported from the Groovy {@code SpringBootRabbitIntegrationTest}. Two Spring
 * Boot apps (a sender and a receiver) round-trip a message through RabbitMQ and both report to one
 * <em>shared</em> test-agent backend (Q8), so a single {@code @RegisterExtension} agent captures
 * the distributed traces from both JVMs.
 *
 * <p>Replaces the dropped {@code TraceStructureWriter} file-mode (Q10) with {@link DecodedSpan}
 * assertions, and <em>hardens</em> the Groovy {@code expectedTraces}. With {@code
 * dd.rabbit.legacy.tracing.enabled=false} the whole round-trip is a single context-propagated trace
 * spanning both JVMs and the broker — a strict parent→child chain of 12 spans, rooted at the sender
 * {@code servlet.request}:
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
 * <p>Asserting that whole chain in one shot ({@link Traces#assertContainsChain}) is stronger than
 * the Groovy set-membership check: it verifies every AMQP operation (publish/deliver/consume, both
 * directions) <em>and</em> its cross-service linkage. The connection setup commands ({@code
 * basic.qos}/{@code basic.consume}/{@code queue.declare}) and {@code basic.ack} are separate
 * single-span traces, asserted per service.
 *
 * <p>Two things the Groovy base did implicitly that this port makes explicit:
 *
 * <ul>
 *   <li><b>Accumulate, don't isolate</b> — {@code .retainAcrossTests()} keeps traces from app
 *       startup onward, because {@code basic.qos}/{@code basic.consume}/{@code queue.declare} are
 *       emitted when the consumers start (before any test method), which a per-method session
 *       {@code clear()} would discard. The Groovy base verified once at {@code cleanupSpec} against
 *       everything written since launch; a single accumulating test method mirrors that.
 *   <li><b>Membership, not count-exact</b> — {@code assertContainsChain} asserts the expected
 *       structure is present and ignores the rest (as the Groovy set-membership check did): the
 *       full distributed span set is large and timing-dependent, so a positional match over the
 *       whole collection is the wrong tool.
 * </ul>
 */
@EnabledIfDockerAvailable
@Testcontainers
class SpringBootRabbitSmokeTest {
  private static final int TIMEOUT_SECONDS = 60;
  private static final int RABBIT_AMQP_PORT = 5672;
  private static final OkHttpClient CLIENT = new OkHttpClient();
  private static final String[] MESSAGES = {"foo", "bar", "baz"};
  // AMQP connection-setup / ack commands each app emits as its own (single-span) trace.
  private static final String[] ADMIN_COMMANDS = {
    "basic.qos", "basic.consume", "basic.ack", "queue.declare"
  };

  // @Testcontainers starts/stops this static container in the class lifecycle (no static-block
  // start()); as a class-level @ExtendWith it runs before the @RegisterExtension fields below, so
  // RabbitMQ is up before the apps launch. Its mapped port is still read lazily at launch via
  // placeholders, since the container is not yet started when these static fields initialize.
  @Container
  private static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.9.20-alpine"));

  // @Order pins the @RegisterExtension order: the shared agent starts before the apps (so its
  // session is open when the consumers emit startup traces) and is torn down after them.
  // retainAcrossTests() keeps those startup traces for the one verifying method.
  @Order(1)
  @RegisterExtension
  static final TestAgentBackend agent =
      TraceBackend.testAgentBuilder().shared().retainAcrossTests().build();

  @Order(2)
  @RegisterExtension
  static final SmokeApp sender = rabbitApp(0).args("--rabbit.sender.queue=otherqueue").build();

  @Order(3)
  @RegisterExtension
  static final SmokeApp receiver =
      rabbitApp(1)
          .args("--rabbit.receiver.queue=otherqueue", "--rabbit.receiver.forward=true")
          .build();

  @Test
  void roundtripsProduceFullAmqpTraceStructure() throws IOException {
    // Drive 3 round-trips through the sender (matches the Groovy `where:` foo/bar/baz); each
    // travels sender -> otherqueue -> receiver -> queue -> sender.
    for (String message : MESSAGES) {
      Request request =
          new Request.Builder().url(sender.url() + "/roundtrip/" + message).get().build();
      try (Response response = CLIENT.newCall(request).execute()) {
        assertEquals(200, response.code(), "roundtrip " + message);
        assertEquals("Got: >" + message, response.body().string(), "roundtrip " + message);
      }
    }

    Traces traces = agent.traces();

    // Each round-trip is its own full distributed trace: a strict parent->child chain across both
    // services and the broker, from the sender's HTTP entrypoint to the sender consuming the
    // forwarded reply. Assert one such chain per message (MESSAGES.length), so all round-trips are
    // verified, not just one.
    traces.assertContainsChain(
        MESSAGES.length,
        TIMEOUT_SECONDS,
        sp("spring-rabbit-0", "servlet.request", "GET /roundtrip/{message}").root(),
        sp("spring-rabbit-0", "spring.handler", "WebController.roundtrip"),
        sp("spring-rabbit-0", "amqp.command", "basic.publish <default> -> otherqueue"),
        sp("rabbitmq", "amqp.deliver", "amqp.deliver otherqueue"),
        sp("spring-rabbit-1", "amqp.command", "basic.deliver otherqueue"),
        sp("spring-rabbit-1", "amqp.consume", "amqp.consume otherqueue"),
        sp("spring-rabbit-1", "spring.consume", "Receiver.receiveMessage"),
        sp("spring-rabbit-1", "amqp.command", "basic.publish <default> -> queue"),
        sp("rabbitmq", "amqp.deliver", "amqp.deliver queue"),
        sp("spring-rabbit-0", "amqp.command", "basic.deliver queue"),
        sp("spring-rabbit-0", "amqp.consume", "amqp.consume queue"),
        sp("spring-rabbit-0", "spring.consume", "Receiver.receiveMessage"));

    // The connection-setup / ack commands each app emits as its own single-span trace.
    for (String service : new String[] {"spring-rabbit-0", "spring-rabbit-1"}) {
      for (String command : ADMIN_COMMANDS) {
        traces.assertContainsChain(TIMEOUT_SECONDS, sp(service, "amqp.command", command).root());
      }
    }
  }

  private static SpanMatcher sp(String service, String operation, String resource) {
    return span().service(service).operationName(operation).resourceName(resource);
  }

  private static SmokeApp.Builder rabbitApp(int index) {
    return SmokeApp.named("spring-rabbit-" + index)
        .jar(System.getProperty("datadog.smoketest.springboot.shadowJar.path"))
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
            "An unexpected connection driver error occurred");
  }
}
