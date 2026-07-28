package datadog.trace.instrumentation.springamqp;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_ANCESTRY;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import rabbit.ErrorMessagingRabbitMQApplication;
import rabbit.ErrorReceiver;
import rabbit.MessagingRabbitMQApplication;
import rabbit.Receiver;
import rabbit.Sender;

/**
 * Instrumentation tests for the spring-rabbit 4.1 module.
 *
 * <p>Validates that the instrumentation creates correct consumer spans with expected operation
 * names, resource names, span types, tags, and parent-child relationships when messages flow
 * through a Spring AMQP {@link
 * org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer}.
 *
 * <p>Covers both the happy path (successful message consumption with trace context propagation) and
 * the error path (exception during message processing sets error tags on the consumer span).
 */
class SpringAmqp41Test extends AbstractInstrumentationTest {

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
   * Verifies that sending a message through Spring AMQP produces the expected trace structure:
   *
   * <ul>
   *   <li>A producer trace with: a parent span and a child {@code amqp.command} span for {@code
   *       basic.publish} with span type {@code queue}
   *   <li>A consumer trace with: a {@code basic.deliver} span (from rabbitmq-amqp instrumentation),
   *       an {@code amqp.consume} child span (from spring-rabbit instrumentation) with resource
   *       name containing the queue name, and a {@code receive} child span (from @Trace annotation
   *       on the receiver method)
   *   <li>An ack trace with a single {@code basic.ack} span
   * </ul>
   *
   * <p>Also verifies distributed trace context propagation: the consumer trace is linked to the
   * producer trace, so the receiver's spans are children of the publish operation.
   */
  @Test
  void tracePropagatedFromProducerToConsumer() throws Exception {
    ConfigurableApplicationContext application = MessagingRabbitMQApplication.run();
    try {
      Sender sender = application.getBean(Sender.class);
      Receiver receiver = application.getBean(Receiver.class);

      // Wait for setup traces (queue declarations, exchanges, bindings) and clear them
      writer.waitForTraces(7);
      tracer.flush();
      writer.start();

      // Send a message under a parent span to test distributed trace propagation
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

      // Assert the full trace structure across all 3 traces
      assertTraces(
          // Trace 1: Producer trace — parent span + amqp.command (basic.publish)
          trace(
              SORT_BY_ANCESTRY,
              // parent span (root of the producer trace)
              span()
                  .operationName(Pattern.compile(Pattern.quote("parent")))
                  .root()
                  .tags(defaultTags(), tag("_dd.svc_src", any())),
              // basic.publish child span — created by rabbitmq-amqp instrumentation
              span()
                  .childOfPrevious()
                  .operationName(Pattern.compile(Pattern.quote("amqp.command")))
                  .resourceName(
                      Pattern.compile(Pattern.quote("basic.publish test-exchange -> foo.bar.foo")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(Tags.SPAN_KIND, any()),
                      tag("amqp.command", any()),
                      tag("amqp.exchange", any()),
                      tag("amqp.routing_key", any()),
                      tag("amqp.delivery_mode", any()),
                      tag("message.size", any()),
                      tag("peer.hostname", any()),
                      tag("peer.port", any()),
                      tag("peer.ipv4", any()),
                      tag("_dd.svc_src", any()))),
          // Trace 2: Consumer trace — basic.deliver + amqp.consume + @Trace receive
          trace(
              SORT_BY_ANCESTRY,
              // basic.deliver root span — created by rabbitmq-amqp instrumentation
              span()
                  .operationName(Pattern.compile(Pattern.quote("amqp.command")))
                  .resourceName(Pattern.compile(Pattern.quote("basic.deliver test-queue")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(Tags.SPAN_KIND, any()),
                      tag("amqp.command", any()),
                      tag("amqp.exchange", any()),
                      tag("amqp.routing_key", any()),
                      tag("amqp.delivery_mode", any()),
                      tag("message.size", any()),
                      tag("peer.hostname", any()),
                      tag("peer.port", any()),
                      tag("peer.ipv4", any()),
                      tag("_dd.svc_src", any())),
              // amqp.consume child span — created by spring-rabbit instrumentation
              span()
                  .childOfPrevious()
                  .operationName(Pattern.compile(Pattern.quote("amqp.consume")))
                  .resourceName(Pattern.compile(Pattern.quote("amqp.consume test-queue")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(
                          Tags.SPAN_KIND,
                          validates(o -> Tags.SPAN_KIND_CONSUMER.equals(String.valueOf(o)))),
                      tag("messaging.system", validates(o -> "rabbitmq".equals(String.valueOf(o)))),
                      tag(
                          "messaging.destination.name",
                          validates(o -> "test-queue".equals(String.valueOf(o)))),
                      tag("amqp.queue", validates(o -> "test-queue".equals(String.valueOf(o)))),
                      tag("message.size", any()),
                      tag("_dd.svc_src", any())),
              // @Trace-annotated receive method
              span()
                  .childOfPrevious()
                  .operationName(Pattern.compile(Pattern.quote("receive")))
                  .resourceName(Pattern.compile(Pattern.quote("Receiver.receiveMessage")))
                  .tags(
                      defaultTags(),
                      tag(Tags.COMPONENT, validates(o -> "trace".contentEquals(String.valueOf(o)))),
                      tag("_dd.svc_src", any()))),
          // Trace 3: Ack trace
          trace(
              span()
                  .operationName(Pattern.compile(Pattern.quote("amqp.command")))
                  .resourceName(Pattern.compile(Pattern.quote("basic.ack")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(Tags.SPAN_KIND, any()),
                      tag("amqp.command", any()),
                      tag("peer.hostname", any()),
                      tag("peer.port", any()),
                      tag("peer.ipv4", any()),
                      tag("_dd.svc_src", any()))));
    } finally {
      application.close();
    }
  }

  /**
   * Verifies that when a message listener throws an exception, the instrumentation correctly:
   *
   * <ul>
   *   <li>Marks the consumer span as errored
   *   <li>Sets {@code error.type} to the exception class name
   *   <li>Sets {@code error.message} to the exception message
   *   <li>Sets {@code error.stack} to the stack trace
   * </ul>
   *
   * <p>This ensures that failures in message processing are properly surfaced in Datadog APM as
   * error spans, enabling alerting and debugging.
   */
  @Test
  void errorInListenerSetsErrorTagsOnConsumerSpan() throws Exception {
    ConfigurableApplicationContext application = ErrorMessagingRabbitMQApplication.run();
    try {
      RabbitTemplate template = application.getBean(RabbitTemplate.class);
      ErrorReceiver errorReceiver = application.getBean(ErrorReceiver.class);

      // Wait for setup traces and clear
      writer.waitForTraces(7);
      tracer.flush();
      writer.start();

      // Send a message to the error exchange that will cause the error receiver to throw
      AgentSpan parentSpan = startSpan("test", "parent");
      try (AgentScope scope = activateSpan(parentSpan)) {
        template.convertAndSend("test-error-exchange", "error.bar.error", "trigger-error");
      } finally {
        parentSpan.finish();
      }

      // Wait for the error receiver to be invoked
      assertTrue(
          errorReceiver.latch.await(5, TimeUnit.SECONDS),
          "Error receiver should be invoked within 5 seconds");

      // Assert the consumer trace contains an errored amqp.consume span with error tags
      assertTraces(
          // Trace 1: Producer trace
          trace(
              SORT_BY_ANCESTRY,
              span()
                  .operationName(Pattern.compile(Pattern.quote("parent")))
                  .root()
                  .tags(defaultTags(), tag("_dd.svc_src", any())),
              span()
                  .childOfPrevious()
                  .operationName(Pattern.compile(Pattern.quote("amqp.command")))
                  .resourceName(
                      Pattern.compile(
                          Pattern.quote("basic.publish test-error-exchange -> error.bar.error")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(Tags.SPAN_KIND, any()),
                      tag("amqp.command", any()),
                      tag("amqp.exchange", any()),
                      tag("amqp.routing_key", any()),
                      tag("amqp.delivery_mode", any()),
                      tag("message.size", any()),
                      tag("peer.hostname", any()),
                      tag("peer.port", any()),
                      tag("peer.ipv4", any()),
                      tag("_dd.svc_src", any()))),
          // Trace 2: Consumer trace with error
          trace(
              SORT_BY_ANCESTRY,
              // basic.deliver
              span()
                  .operationName(Pattern.compile(Pattern.quote("amqp.command")))
                  .resourceName(Pattern.compile(Pattern.quote("basic.deliver test-error-queue")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(Tags.SPAN_KIND, any()),
                      tag("amqp.command", any()),
                      tag("amqp.exchange", any()),
                      tag("amqp.routing_key", any()),
                      tag("amqp.delivery_mode", any()),
                      tag("message.size", any()),
                      tag("peer.hostname", any()),
                      tag("peer.port", any()),
                      tag("peer.ipv4", any()),
                      tag("_dd.svc_src", any())),
              // amqp.consume — should be marked as errored with error tags
              span()
                  .childOfPrevious()
                  .operationName(Pattern.compile(Pattern.quote("amqp.consume")))
                  .resourceName(Pattern.compile(Pattern.quote("amqp.consume test-error-queue")))
                  .type("queue")
                  .error()
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(
                          Tags.SPAN_KIND,
                          validates(o -> Tags.SPAN_KIND_CONSUMER.equals(String.valueOf(o)))),
                      tag("messaging.system", validates(o -> "rabbitmq".equals(String.valueOf(o)))),
                      tag(
                          "messaging.destination.name",
                          validates(o -> "test-error-queue".equals(String.valueOf(o)))),
                      tag(
                          "amqp.queue",
                          validates(o -> "test-error-queue".equals(String.valueOf(o)))),
                      tag("message.size", any()),
                      error(ListenerExecutionFailedException.class),
                      tag("error.message", any()),
                      tag("_dd.svc_src", any())),
              // @Trace-annotated receive method — also errored since it threw the exception
              span()
                  .childOfPrevious()
                  .operationName(Pattern.compile(Pattern.quote("receive")))
                  .resourceName(Pattern.compile(Pattern.quote("ErrorReceiver.receiveMessage")))
                  .error()
                  .tags(
                      defaultTags(),
                      tag(Tags.COMPONENT, validates(o -> "trace".contentEquals(String.valueOf(o)))),
                      error(
                          AmqpRejectAndDontRequeueException.class, "Simulated processing failure"),
                      tag("_dd.svc_src", any()))),
          // Trace 3: Ack/Nack trace
          trace(
              span()
                  .operationName(Pattern.compile(Pattern.quote("amqp.command")))
                  .type("queue")
                  .tags(
                      defaultTags(),
                      tag(
                          Tags.COMPONENT,
                          validates(o -> "rabbitmq-amqp".contentEquals(String.valueOf(o)))),
                      tag(Tags.SPAN_KIND, any()),
                      tag("amqp.command", any()),
                      tag("peer.hostname", any()),
                      tag("peer.port", any()),
                      tag("peer.ipv4", any()),
                      tag("_dd.svc_src", any()))));
    } finally {
      application.close();
    }
  }
}
