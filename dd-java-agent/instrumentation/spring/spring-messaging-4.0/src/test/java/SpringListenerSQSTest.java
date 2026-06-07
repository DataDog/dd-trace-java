import static datadog.trace.agent.test.assertions.Matchers.any;
import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceAssertions.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.agent.test.assertions.TraceAssertions;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.config.WithConfig;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import listener.Config;
import listener.TestListener;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.support.GenericMessage;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@WithConfig(key = "service.name", value = "my-service")
public class SpringListenerSQSTest extends AbstractInstrumentationTest {

  // Trace context carried in the message body so the consumer continues a remote trace.
  private static final long EMBEDDED_TRACE_ID = 4948377316357291421L;
  private static final long EMBEDDED_PARENT_ID = 6746998015037429512L;
  private static final String EMBEDDED_DATADOG_HEADER =
      "{\"x-datadog-trace-id\": \"4948377316357291421\", "
          + "\"x-datadog-parent-id\": \"6746998015037429512\", "
          + "\"x-datadog-sampling-priority\": \"1\"}";

  @Test
  void receivingMessageContextUsedWhenNoImmediateContext() throws Exception {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(Config.class);
    try {
      InetSocketAddress address =
          context.getBean(SQSRestServer.class).waitUntilStarted().localAddress();
      SqsTemplate template = SqsTemplate.newTemplate(context.getBean(SqsAsyncClient.class));
      writer.waitForTraces(2);
      writer.clear();

      sendUnderParent(template, "SpringListenerSQS", "a message");

      blockUntilTracesMatch(traces -> traces.size() >= 4);
      List<DDSpan> parentTrace = sortedParentTrace();
      long parentId = parentTrace.get(0).getSpanId();
      long sendingSpanId = parentTrace.get(2).getSpanId();

      assertTraces(
          SORT_BY_START_TIME,
          trace(
              TraceMatcher.SORT_BY_START_TIME,
              parentSpan(),
              getQueueUrl(address, parentId, "SpringListenerSQS"),
              sendMessage(address, parentId, "SpringListenerSQS")),
          trace(receiveMessage(address, sendingSpanId, "SpringListenerSQS")),
          trace(springSqsListener(sendingSpanId, "TestListener.observe")),
          trace(deleteMessageBatch(address, "SpringListenerSQS")));
    } finally {
      context.close();
    }
  }

  @Test
  void embeddedDatadogContextUsedWhenNoImmediateContext() throws Exception {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(Config.class);
    try {
      InetSocketAddress address =
          context.getBean(SQSRestServer.class).waitUntilStarted().localAddress();
      SqsTemplate template = SqsTemplate.newTemplate(context.getBean(SqsAsyncClient.class));
      writer.waitForTraces(2);
      writer.clear();

      Map<String, Object> headers = new HashMap<>();
      headers.put("_datadog", EMBEDDED_DATADOG_HEADER);
      GenericMessage<String> message = new GenericMessage<>("a message", headers);
      sendUnderParent(template, "SpringListenerSQS", message);

      blockUntilTracesMatch(traces -> traces.size() >= 4);
      long parentId = sortedParentTrace().get(0).getSpanId();

      assertTraces(
          SORT_BY_START_TIME,
          trace(
              TraceMatcher.SORT_BY_START_TIME,
              parentSpan(),
              getQueueUrl(address, parentId, "SpringListenerSQS"),
              sendMessage(address, parentId, "SpringListenerSQS")),
          // ReceiveMessage continues the trace embedded in the message body.
          trace(
              span()
                  .serviceName("sqs")
                  .operationName(text("aws.http"))
                  .resourceName(text("Sqs.ReceiveMessage"))
                  .type(DDSpanTypes.MESSAGE_CONSUMER)
                  .error(false)
                  .traceId(DDTraceId.from(EMBEDDED_TRACE_ID))
                  .childOf(EMBEDDED_PARENT_ID)
                  .tags(
                      str(Tags.COMPONENT, "java-aws-sdk"),
                      str(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER),
                      str("aws.service", "Sqs"),
                      str("aws_service", "Sqs"),
                      str("aws.operation", "ReceiveMessage"),
                      str("aws.agent", "java-aws-sdk"),
                      str("aws.queue.url", queueUrl(address, "SpringListenerSQS")),
                      requestId(),
                      defaultTags(),
                      propagationDm(),
                      propagationTid(),
                      serviceSource())),
          // spring.consume also continues the embedded trace.
          trace(
              span()
                  .serviceName("my-service")
                  .operationName(text("spring.consume"))
                  .resourceName(text("TestListener.observe"))
                  .type(DDSpanTypes.MESSAGE_CONSUMER)
                  .error(false)
                  .traceId(DDTraceId.from(EMBEDDED_TRACE_ID))
                  .childOf(EMBEDDED_PARENT_ID)
                  .tags(
                      str(Tags.COMPONENT, "spring-messaging"),
                      str(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER),
                      defaultTags(),
                      propagationDm(),
                      propagationTid(),
                      serviceSource())),
          trace(deleteMessageBatch(address, "SpringListenerSQS")));
    } finally {
      context.close();
    }
  }

  @Test
  void asyncHandlerKeepsSpringConsumeSpanActiveDuringCompletableFutureExecution() throws Exception {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(Config.class);
    TestListener listener = context.getBean(TestListener.class);
    listener.prepareAsyncObservation();
    try {
      InetSocketAddress address =
          context.getBean(SQSRestServer.class).waitUntilStarted().localAddress();
      SqsTemplate template = SqsTemplate.newTemplate(context.getBean(SqsAsyncClient.class));
      writer.waitForTraces(2);
      writer.clear();

      sendUnderParent(template, "SpringListenerSQSAsync", "an async message");
      listener.awaitAsyncStarted();

      // While the coroutine work is blocked, only the parent and receive traces are written, and
      // the spring.consume span is still open (not finished).
      writer.waitForTraces(2);
      assertEquals(2, writer.size());
      assertEquals(Boolean.FALSE, listener.getActiveParentFinished());

      listener.releaseAsyncObservation();

      blockUntilTracesMatch(traces -> traces.size() >= 4);
      List<DDSpan> parentTrace = sortedParentTrace();
      long parentId = parentTrace.get(0).getSpanId();
      long sendingSpanId = parentTrace.get(2).getSpanId();

      assertTraces(
          SORT_BY_START_TIME,
          trace(
              TraceMatcher.SORT_BY_START_TIME,
              parentSpan(),
              getQueueUrl(address, parentId, "SpringListenerSQSAsync"),
              sendMessage(address, parentId, "SpringListenerSQSAsync")),
          trace(receiveMessage(address, sendingSpanId, "SpringListenerSQSAsync")),
          trace(
              TraceMatcher.SORT_BY_START_TIME,
              springSqsListener(sendingSpanId, "TestListener.observeAsync"),
              // Child span created inside the CompletableFuture proves spring.consume was active.
              span()
                  .operationName(text("async.child"))
                  .childOfPrevious()
                  .tags(defaultTags(), serviceSource())),
          trace(deleteMessageBatch(address, "SpringListenerSQSAsync")));
    } finally {
      listener.releaseAsyncObservation();
      context.close();
    }
  }

  private void sendUnderParent(SqsTemplate template, String queue, Object payload)
      throws Exception {
    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      template.sendAsync(queue, payload).get();
    } finally {
      scope.close();
      parent.finish();
    }
  }

  /** The earliest-started trace (the local "parent" trace), with its spans sorted by start time. */
  private List<DDSpan> sortedParentTrace() {
    List<List<DDSpan>> snapshot = new ArrayList<>(writer);
    snapshot.sort(TraceAssertions.TRACE_START_TIME_COMPARATOR);
    List<DDSpan> parentTrace = new ArrayList<>(snapshot.get(0));
    parentTrace.sort(TraceMatcher.START_TIME_COMPARATOR);
    return parentTrace;
  }

  private static SpanMatcher parentSpan() {
    return span()
        .operationName(text("parent"))
        .resourceName(text("parent"))
        .root()
        .tags(defaultTags(), propagationDm(), propagationTid(), serviceSource());
  }

  private static SpanMatcher getQueueUrl(
      InetSocketAddress address, long parentId, String queueName) {
    return span()
        .serviceName("java-aws-sdk")
        .operationName(text("aws.http"))
        .resourceName(text("Sqs.GetQueueUrl"))
        .type(DDSpanTypes.HTTP_CLIENT)
        .error(false)
        .childOf(parentId)
        .tags(
            str(Tags.COMPONENT, "java-aws-sdk"),
            str(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT),
            str(Tags.HTTP_METHOD, "POST"),
            num(Tags.HTTP_STATUS, 200),
            num(Tags.PEER_PORT, address.getPort()),
            str(Tags.PEER_HOSTNAME, "localhost"),
            str("aws.service", "Sqs"),
            str("aws_service", "Sqs"),
            str("aws.operation", "GetQueueUrl"),
            str("aws.agent", "java-aws-sdk"),
            str("aws.queue.name", queueName),
            requestId(),
            str("queuename", queueName),
            tag("http.url", any()),
            tag("http.query.string", any()),
            defaultTags(),
            propagationDm(),
            propagationTid(),
            serviceSource());
  }

  private static SpanMatcher sendMessage(
      InetSocketAddress address, long parentId, String queueName) {
    return span()
        .serviceName("sqs")
        .operationName(text("aws.http"))
        .resourceName(text("Sqs.SendMessage"))
        .type(DDSpanTypes.HTTP_CLIENT)
        .error(false)
        .childOf(parentId)
        .tags(
            str(Tags.COMPONENT, "java-aws-sdk"),
            str(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT),
            str(Tags.HTTP_METHOD, "POST"),
            num(Tags.HTTP_STATUS, 200),
            num(Tags.PEER_PORT, address.getPort()),
            str(Tags.PEER_HOSTNAME, "localhost"),
            str("aws.service", "Sqs"),
            str("aws_service", "Sqs"),
            str("aws.operation", "SendMessage"),
            str("aws.agent", "java-aws-sdk"),
            str("aws.queue.url", queueUrl(address, queueName)),
            requestId(),
            tag("http.url", any()),
            tag("http.query.string", any()),
            defaultTags(),
            propagationDm(),
            propagationTid(),
            serviceSource());
  }

  private static SpanMatcher receiveMessage(
      InetSocketAddress address, long parentId, String queueName) {
    return span()
        .serviceName("sqs")
        .operationName(text("aws.http"))
        .resourceName(text("Sqs.ReceiveMessage"))
        .type(DDSpanTypes.MESSAGE_CONSUMER)
        .error(false)
        .childOf(parentId)
        .tags(
            str(Tags.COMPONENT, "java-aws-sdk"),
            str(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER),
            str("aws.service", "Sqs"),
            str("aws_service", "Sqs"),
            str("aws.operation", "ReceiveMessage"),
            str("aws.agent", "java-aws-sdk"),
            str("aws.queue.url", queueUrl(address, queueName)),
            requestId(),
            defaultTags(),
            propagationDm(),
            propagationTid(),
            serviceSource());
  }

  private static SpanMatcher springSqsListener(long parentId, String resourceName) {
    return span()
        .serviceName("my-service")
        .operationName(text("spring.consume"))
        .resourceName(text(resourceName))
        .type(DDSpanTypes.MESSAGE_CONSUMER)
        .error(false)
        .childOf(parentId)
        .tags(
            str(Tags.COMPONENT, "spring-messaging"),
            str(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER),
            defaultTags(),
            propagationDm(),
            propagationTid(),
            serviceSource());
  }

  private static SpanMatcher deleteMessageBatch(InetSocketAddress address, String queueName) {
    return span()
        .serviceName("sqs")
        .operationName(text("aws.http"))
        .resourceName(text("Sqs.DeleteMessageBatch"))
        .type(DDSpanTypes.HTTP_CLIENT)
        .error(false)
        .root()
        .tags(
            str(Tags.COMPONENT, "java-aws-sdk"),
            str(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT),
            str(Tags.HTTP_METHOD, "POST"),
            num(Tags.HTTP_STATUS, 200),
            num(Tags.PEER_PORT, address.getPort()),
            str(Tags.PEER_HOSTNAME, "localhost"),
            str("aws.service", "Sqs"),
            str("aws_service", "Sqs"),
            str("aws.operation", "DeleteMessageBatch"),
            str("aws.agent", "java-aws-sdk"),
            str("aws.queue.url", queueUrl(address, queueName)),
            requestId(),
            tag("http.url", any()),
            tag("http.query.string", any()),
            defaultTags(),
            propagationDm(),
            propagationTid(),
            serviceSource());
  }

  private static String queueUrl(InetSocketAddress address, String queueName) {
    return "http://localhost:" + address.getPort() + "/000000000000/" + queueName;
  }

  /**
   * elasticmq <1.6 returned the zero-UUID request id over the XML/query protocol; elasticmq 1.6.x
   * JSON responses omit the x-amzn-RequestId header, so the tracer records "UNKNOWN". Accept both
   * so the test is tolerant of the elasticmq version on the classpath.
   */
  private static TagsMatcher requestId() {
    return tag(
        "aws.requestId",
        validates(v -> "00000000-0000-0000-0000-000000000000".equals(v) || "UNKNOWN".equals(v)));
  }

  /** String tag matcher tolerant of CharSequence tag values (e.g. UTF8BytesString). */
  private static TagsMatcher str(String name, String expected) {
    return tag(name, validates(v -> expected.equals(String.valueOf(v))));
  }

  /** Numeric tag matcher tolerant of boxed Integer/Long tag values. */
  private static TagsMatcher num(String name, int expected) {
    return tag(name, validates(v -> Integer.toString(expected).equals(String.valueOf(v))));
  }

  // Propagation tags may or may not be present depending on the trace; any() is dropped when
  // absent, so declaring them keeps the strict matcher from flagging them as unexpected.
  private static TagsMatcher propagationDm() {
    return tag("_dd.p.dm", any());
  }

  private static TagsMatcher propagationTid() {
    return tag("_dd.p.tid", any());
  }

  // Emitted on spans when the service name is overridden via @WithConfig; optional via any().
  private static TagsMatcher serviceSource() {
    return tag("_dd.svc_src", any());
  }

  // Operation/resource names are stored as UTF8BytesString; compare as CharSequence via a pattern
  // (an exact String matcher would fail the String#equals(UTF8BytesString) type check).
  private static Pattern text(String value) {
    return Pattern.compile(Pattern.quote(value));
  }
}
