package datadog.trace.instrumentation.springamqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.DDTags;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import rabbit.MessagingRabbitMQApplication;
import rabbit.Receiver;
import rabbit.Sender;

/**
 * Forked test to verify DSM (Data Streams Monitoring) checkpoints are set on spring-rabbit consumer
 * spans. Runs in a separate JVM with {@code dd.data.streams.enabled=true} so the tracer's {@link
 * datadog.trace.core.datastreams.DefaultDataStreamsMonitoring} is active and sets the {@code
 * pathway.hash} tag on spans that receive a DSM checkpoint.
 */
@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "true")
class SpringAmqp41DataStreamsForkedTest extends AbstractInstrumentationTest {

  private static RabbitMQContainer rabbit;

  @BeforeAll
  static void setUpRabbitMQ() {
    rabbit = new RabbitMQContainer("rabbitmq:3.9.20-alpine");
    rabbit.start();
    String hostName = rabbit.getHost();
    int port = rabbit.getMappedPort(5672);
    MessagingRabbitMQApplication.hostName = hostName;
    MessagingRabbitMQApplication.port = port;
    PortUtils.waitForPortToOpen(hostName, port, 5, TimeUnit.SECONDS);
  }

  @AfterAll
  static void tearDownRabbitMQ() {
    if (rabbit != null) {
      rabbit.close();
    }
  }

  /**
   * Verifies that the {@code amqp.consume} span created by the spring-rabbit instrumentation
   * receives a DSM checkpoint, resulting in a {@code pathway.hash} tag on the span. This confirms
   * that Data Streams Monitoring can track message pipeline latency through Spring AMQP consumers.
   */
  @Test
  void consumerSpanHasPathwayHashTag() throws Exception {
    ConfigurableApplicationContext application = MessagingRabbitMQApplication.run();
    try {
      Sender sender = application.getBean(Sender.class);
      Receiver receiver = application.getBean(Receiver.class);

      // Wait for setup traces (queue declarations, exchanges, bindings) and clear them
      writer.waitForTraces(7);
      tracer.flush();
      writer.start();

      // Send a message under a parent span
      AgentSpan parentSpan = startSpan("test", "parent");
      try (AgentScope scope = activateSpan(parentSpan)) {
        sender.send("foo", "hello");
      } finally {
        parentSpan.finish();
      }

      // Wait for the receiver to process the message
      assertTrue(
          receiver.latch.await(5, TimeUnit.SECONDS),
          "Receiver should process the message within 5 seconds");

      // Wait for traces to arrive
      writer.waitForTraces(3);

      // Find the amqp.consume span and verify it has the pathway.hash tag
      DDSpan consumeSpan = null;
      for (List<DDSpan> trace : writer) {
        for (DDSpan span : trace) {
          if ("amqp.consume".equals(span.getOperationName().toString())) {
            consumeSpan = span;
            break;
          }
        }
        if (consumeSpan != null) {
          break;
        }
      }

      assertNotNull(consumeSpan, "Expected to find an amqp.consume span");
      assertNotNull(
          consumeSpan.getTag(DDTags.PATHWAY_HASH),
          "amqp.consume span should have pathway.hash tag set by DSM checkpoint");
    } finally {
      application.close();
    }
  }
}
