package datadog.smoketest;

import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.SORT_BY_PARENT_CHAIN;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.smoketest.backend.EnabledIfDockerAvailable;
import datadog.smoketest.backend.TestAgentBackend;
import datadog.smoketest.backend.TraceBackend;
import datadog.smoketest.backend.Traces;
import datadog.smoketest.trace.SmokeTraceAssertions;
import datadog.smoketest.trace.SpanMatcher;
import datadog.smoketest.trace.TraceMatcher;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * <p>The whole collection is asserted with the standard {@link Traces#assertTraces} DSL in
 * {@linkplain SmokeTraceAssertions order-independent subset} mode ({@code
 * unordered().ignoreAdditionalTraces()}): each matcher matches a distinct received trace and extras
 * are ignored. This is stronger than the Groovy set-membership check — each round-trip trace is
 * matched count-exact (all 12 spans, in {@link TraceMatcher#SORT_BY_PARENT_CHAIN parent-chain
 * order}), verifying every AMQP operation (publish/deliver/consume, both directions) <em>and</em>
 * its cross-service linkage — while staying robust to the timing-dependent extras (the broker emits
 * its connection-setup commands and per-ack traces as their own single-span traces, in
 * non-deterministic count and order).
 *
 * <p>Two design points, informed by inspecting the live trace collection:
 *
 * <ul>
 *   <li><b>Parent-chain order, not start time</b> — the 12-span round-trip is a strict linear
 *       chain, but its spans start within the same tick and race, so {@code SORT_BY_START_TIME} is
 *       unstable across runs. {@code SORT_BY_PARENT_CHAIN} orders spans by parent links
 *       (timestamp-independent) and asserts the trace is exactly that chain.
 *   <li><b>Accumulate, don't isolate</b> — {@code .retainAcrossTests()} keeps traces from app
 *       startup onward, because {@code basic.qos}/{@code basic.consume}/{@code queue.declare} are
 *       emitted when the consumers start (before any test method), which a per-method session
 *       {@code clear()} would discard. Mirrors the Groovy base verifying once at {@code
 *       cleanupSpec} against everything since launch.
 * </ul>
 */
@EnabledIfDockerAvailable
@Testcontainers
class SpringBootRabbitSmokeTest {
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
    // Drive 3 round-trips through the sender;
    // Each travels sender -> otherqueue -> receiver -> queue -> sender.
    String[] MESSAGES = {"foo", "bar", "baz"};
    for (String message : MESSAGES) {
      Request request =
          new Request.Builder().url(sender.url() + "/roundtrip/" + message).get().build();
      try (Response response = CLIENT.newCall(request).execute()) {
        assertEquals(200, response.code(), "roundtrip " + message);
        assertEquals("Got: >" + message, response.body().string(), "roundtrip " + message);
      }
    }

    // One order-independent subset assertion over the whole collection (unordered +
    // ignoreAdditionalTraces): each matcher must match a distinct received trace, and
    // unrelated/duplicate traces (extra acks, etc.) are ignored. Assert one full round-trip trace
    // per message plus each service's connection-setup/ack commands.
    List<TraceMatcher> expected = new ArrayList<>();
    for (int i = 0; i < MESSAGES.length; i++) {
      expected.add(roundTrip()); // one full round-trip trace per message => all are verified
    }
    for (String service : new String[] {"spring-rabbit-0", "spring-rabbit-1"}) {
      for (String command : ADMIN_COMMANDS) {
        expected.add(admin(service, command));
      }
    }
    agent
        .traces()
        .assertTraces(
            TIMEOUT_SECONDS,
            o -> o.unordered().ignoreAdditionalTraces(),
            expected.toArray(new TraceMatcher[0]));
  }

  // The full distributed round-trip as one strict parent->child chain across both services and the
  // broker. SORT_BY_PARENT_CHAIN orders the spans root->leaf and asserts the trace IS exactly this
  // linear chain: HTTP entrypoint -> publish -> receiver consumes+forwards -> sender consumes
  // reply.
  private static TraceMatcher roundTrip() {
    return trace(
        SORT_BY_PARENT_CHAIN,
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
  }

  // A connection-setup / ack command emitted as its own single-span (root) trace.
  private static TraceMatcher admin(String service, String command) {
    return trace(sp(service, "amqp.command", command).root());
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
