import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.amazon.sqs.javamessaging.ProviderConfiguration;
import com.amazon.sqs.javamessaging.SQSConnectionFactory;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.TraceUtils;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.aws.v2.sqs.TracingList;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@WithConfig(key = GeneralConfig.SERVICE_NAME, value = "A-service")
abstract class SqsClientTestBase extends AbstractInstrumentationTest {
  private static final AnonymousCredentialsProvider CREDENTIALS_PROVIDER =
      AnonymousCredentialsProvider.create();
  private static final AtomicInteger QUEUE_COUNTER = new AtomicInteger();
  private static SQSRestServer server;
  private static URI endpoint;

  @BeforeAll
  static void startSqsServer() {
    server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start();
    InetSocketAddress address = server.waitUntilStarted().localAddress();
    endpoint = URI.create("http://localhost:" + address.getPort());
  }

  @AfterAll
  static void stopSqsServer() {
    if (server != null) {
      server.stopAndWait();
      server = null;
    }
  }

  @BeforeEach
  void setUpAwsCredentials() {
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key");
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key");
  }

  @Test
  void traceDetailsPropagatedViaSqsSystemMessageAttributes() throws Exception {
    try (SqsClient client = newClient()) {
      String queueUrl = createQueue(client);
      writer.clear();

      TraceUtils.runUnderTrace(
          "parent",
          () -> {
            client.sendMessage(
                SendMessageRequest.builder().queueUrl(queueUrl).messageBody("sometext").build());
            return null;
          });
      List<Message> messages =
          client
              .receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build())
              .messages();
      messages.forEach(message -> {});

      DDSpan sendSpan = assertSqsSendReceiveTraces(queueUrl);
      assertEquals(1, messages.size());

      String awsTraceHeader = messages.get(0).attributesAsStrings().get("AWSTraceHeader");
      assertNotNull(awsTraceHeader);
      assertTrue(
          awsTraceHeader.matches(
              "Root=1-[0-9a-f]{8}-00000000"
                  + sendSpan.getTraceId().toHexStringPadded(16)
                  + ";Parent="
                  + DDSpanId.toHexStringPadded(sendSpan.getSpanId())
                  + ";Sampled=1"));
    }
  }

  @Test
  @WithConfig(key = "sqs.inject.datadog.attribute.enabled", value = "false")
  void datadogContextIsNotInjectedIfSqsInjectDatadogAttributeIsDisabled() throws Exception {
    try (SqsClient client = newClient()) {
      String queueUrl = createQueue(client);
      writer.clear();

      client.sendMessage(
          SendMessageRequest.builder().queueUrl(queueUrl).messageBody("sometext").build());
      List<Message> messages =
          client
              .receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build())
              .messages();

      assertEquals(1, messages.size());
      assertFalse(messages.get(0).messageAttributes().containsKey("_datadog"));
    }
  }

  @Test
  void apmTraceContextIsInjectedIntoDatadogMessageAttributeOnSend() throws Exception {
    try (SqsClient client = newClient()) {
      String queueUrl = createQueue(client);
      writer.clear();

      TraceUtils.runUnderTrace(
          "parent",
          () -> {
            client.sendMessage(
                SendMessageRequest.builder().queueUrl(queueUrl).messageBody("sometext").build());
            return null;
          });
      List<Message> messages =
          client
              .receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build())
              .messages();
      messages.forEach(message -> {});

      DDSpan sendSpan = assertSqsSendReceiveTraces(queueUrl);
      MessageAttributeValue datadogAttribute = messages.get(0).messageAttributes().get("_datadog");
      assertNotNull(datadogAttribute);
      assertEquals("String", datadogAttribute.dataType());
      assertTrue(datadogAttribute.stringValue().contains("\"x-datadog-trace-id\""));
      assertTrue(datadogAttribute.stringValue().contains(sendSpan.getTraceId().toString()));
      assertTrue(datadogAttribute.stringValue().contains("\"x-datadog-parent-id\""));
      assertTrue(
          datadogAttribute.stringValue().contains(Long.toUnsignedString(sendSpan.getSpanId())));
    }
  }

  @Test
  void traceDetailsPropagatedViaEmbeddedSqsMessageAttributeString() throws Exception {
    assumeFalse(isDataStreamsEnabled());

    writer.clear();
    Message message =
        Message.builder()
            .messageAttributes(
                Collections.singletonMap(
                    "_datadog",
                    MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(
                            "{\"x-datadog-trace-id\": \"4948377316357291421\", "
                                + "\"x-datadog-parent-id\": \"6746998015037429512\", "
                                + "\"x-datadog-sampling-priority\": \"1\"}")
                        .build()))
            .build();
    List<Message> messages =
        new TracingList(
            Collections.singletonList(message), expectedQueueUrl("somequeue"), requestId());

    messages.forEach(ignored -> {});

    assertEmbeddedReceiveSpan("somequeue");
  }

  @Test
  void traceDetailsPropagatedViaEmbeddedSqsMessageAttributeBinary() throws Exception {
    assumeFalse(isDataStreamsEnabled());

    assertEmbeddedBinaryHeader(
        UTF_8.encode(
            "{\"x-datadog-trace-id\":\"4948377316357291421\","
                + "\"x-datadog-parent-id\":\"6746998015037429512\","
                + "\"x-datadog-sampling-priority\":\"1\"}"));
    assertEmbeddedBinaryHeader(
        UTF_8.encode(
            "eyJ4LWRhdGFkb2ctdHJhY2UtaWQiOiI0OTQ4Mzc3MzE2MzU3MjkxNDIxIiwieC1kYXRhZG9n"
                + "LXBhcmVudC1pZCI6IjY3NDY5OTgwMTUwMzc0Mjk1MTIiLCJ4LWRhdGFkb2ctc2FtcGxpbmct"
                + "cHJpb3JpdHkiOiIxIn0="));
  }

  @Test
  void traceDetailsPropagatedFromSqsToJms() throws Exception {
    assumeFalse(isDataStreamsEnabled());

    try (SqsClient client = newClient()) {
      String queueName = queueName();
      String queueUrl =
          client.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).queueUrl();
      SQSConnectionFactory connectionFactory =
          new SQSConnectionFactory(new ProviderConfiguration(), client);
      Connection connection = connectionFactory.createConnection();
      Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
      Queue queue = session.createQueue(queueName);
      MessageConsumer consumer = session.createConsumer(queue);

      writer.clear();

      MessageAttributeValue datadogAttribute =
          MessageAttributeValue.builder()
              .dataType("Binary")
              .binaryValue(SdkBytes.fromUtf8String("hello world"))
              .build();

      try {
        connection.start();
        TraceUtils.runUnderTrace(
            "parent",
            () -> {
              client.sendMessage(
                  SendMessageRequest.builder()
                      .queueUrl(queueUrl)
                      .messageBody("sometext")
                      .messageAttributes(Collections.singletonMap("_datadog", datadogAttribute))
                      .build());
              return null;
            });
        javax.jms.Message message = consumer.receive();
        consumer.receiveNoWait();

        writer.waitForTraces(4);

        DDSpan parentSpan = findSpanByResource("parent");
        DDSpan sendSpan = findSpanByResource("Sqs.SendMessage");
        DDSpan receiveSpan = findSpanByResource("Sqs.ReceiveMessage");
        DDSpan deleteSpan = findSpanByResource("Sqs.DeleteMessage");
        DDSpan jmsSpan = findSpanByResource("Consumed from Queue " + queue.getQueueName());

        assertTestSpan(parentSpan, 0);
        assertAwsSpan(
            sendSpan,
            expectedService("Sqs", "SendMessage"),
            expectedOperation("Sqs", "SendMessage"),
            "Sqs.SendMessage",
            DDSpanTypes.HTTP_CLIENT,
            Tags.SPAN_KIND_CLIENT,
            "SendMessage",
            parentSpan.getSpanId(),
            queueUrl);
        assertEquals(parentSpan.getTraceId(), sendSpan.getTraceId());

        assertAwsSpan(
            receiveSpan,
            expectedService("Sqs", "ReceiveMessage"),
            expectedOperation("Sqs", "ReceiveMessage"),
            "Sqs.ReceiveMessage",
            DDSpanTypes.MESSAGE_CONSUMER,
            Tags.SPAN_KIND_CONSUMER,
            "ReceiveMessage",
            sendSpan.getSpanId(),
            queueUrl);
        assertEquals(sendSpan.getTraceId(), receiveSpan.getTraceId());

        assertAwsSpan(
            deleteSpan,
            expectedService("Sqs", "DeleteMessage"),
            expectedOperation("Sqs", "DeleteMessage"),
            "Sqs.DeleteMessage",
            DDSpanTypes.HTTP_CLIENT,
            Tags.SPAN_KIND_CLIENT,
            "DeleteMessage",
            0,
            queueUrl);

        assertNotNull(jmsSpan);
        assertEquals(expectedJmsService(), jmsSpan.getServiceName());
        assertEquals(expectedJmsOperation(), jmsSpan.getOperationName().toString());
        assertEquals(DDSpanTypes.MESSAGE_CONSUMER, jmsSpan.getSpanType());
        assertEquals(sendSpan.getSpanId(), jmsSpan.getParentId());
        assertEquals(sendSpan.getTraceId(), jmsSpan.getTraceId());
        assertEquals("jms", tagValue(jmsSpan, Tags.COMPONENT));
        assertEquals(Tags.SPAN_KIND_CONSUMER, tagValue(jmsSpan, Tags.SPAN_KIND));
        assertNotNull(jmsSpan.getTag(InstrumentationTags.RECORD_QUEUE_TIME_MS));

        String expectedTraceProperty =
            "X-Amzn-Trace-Id".toLowerCase(Locale.ENGLISH).replace("-", "__dash__");
        assertTrue(
            message
                .getStringProperty(expectedTraceProperty)
                .matches(
                    "Root=1-[0-9a-f]{8}-00000000"
                        + sendSpan.getTraceId().toHexStringPadded(16)
                        + ";Parent="
                        + DDSpanId.toHexStringPadded(sendSpan.getSpanId())
                        + ";Sampled=1"));
        assertFalse(message.propertyExists("_datadog"));
      } finally {
        session.close();
        connection.stop();
      }
    }
  }

  abstract String expectedOperation(String awsService, String awsOperation);

  abstract String expectedService(String awsService, String awsOperation);

  boolean isDataStreamsEnabled() {
    return false;
  }

  private SqsClient newClient() {
    return SqsClient.builder()
        .region(Region.EU_CENTRAL_1)
        .endpointOverride(endpoint)
        .credentialsProvider(CREDENTIALS_PROVIDER)
        .build();
  }

  private static String createQueue(SqsClient client) {
    return client
        .createQueue(CreateQueueRequest.builder().queueName(queueName()).build())
        .queueUrl();
  }

  private static String queueName() {
    return "somequeue" + QUEUE_COUNTER.incrementAndGet();
  }

  private DDSpan assertSqsSendReceiveTraces(String queueUrl) throws Exception {
    writer.waitForTraces(2);

    DDSpan parentSpan = findSpanByResource("parent");
    DDSpan sendSpan = findSpanByResource("Sqs.SendMessage");
    DDSpan receiveSpan = findSpanByResource("Sqs.ReceiveMessage");

    assertEquals(2, writer.size(), () -> "Unexpected traces: " + writer);
    assertEquals(3, spanCount(), () -> "Unexpected spans: " + writer);

    assertTestSpan(parentSpan, 0);
    assertAwsSpan(
        sendSpan,
        expectedService("Sqs", "SendMessage"),
        expectedOperation("Sqs", "SendMessage"),
        "Sqs.SendMessage",
        DDSpanTypes.HTTP_CLIENT,
        Tags.SPAN_KIND_CLIENT,
        "SendMessage",
        parentSpan.getSpanId(),
        queueUrl);
    assertEquals(parentSpan.getTraceId(), sendSpan.getTraceId());

    assertAwsSpan(
        receiveSpan,
        expectedService("Sqs", "ReceiveMessage"),
        expectedOperation("Sqs", "ReceiveMessage"),
        "Sqs.ReceiveMessage",
        DDSpanTypes.MESSAGE_CONSUMER,
        Tags.SPAN_KIND_CONSUMER,
        "ReceiveMessage",
        sendSpan.getSpanId(),
        queueUrl);
    assertEquals(sendSpan.getTraceId(), receiveSpan.getTraceId());

    return sendSpan;
  }

  private void assertEmbeddedBinaryHeader(ByteBuffer headerValue) throws Exception {
    writer.clear();
    Message message =
        Message.builder()
            .messageAttributes(
                Collections.singletonMap(
                    "_datadog",
                    MessageAttributeValue.builder()
                        .dataType("Binary")
                        .binaryValue(SdkBytes.fromByteBuffer(headerValue))
                        .build()))
            .build();
    List<Message> messages =
        new TracingList(
            Collections.singletonList(message), expectedQueueUrl("somequeue"), requestId());

    messages.forEach(ignored -> {});

    assertEmbeddedReceiveSpan("somequeue");
  }

  private void assertEmbeddedReceiveSpan(String queueName) throws Exception {
    writer.waitForTraces(1);

    DDSpan receiveSpan = findSpanByResource("Sqs.ReceiveMessage");
    assertEquals(1, writer.size(), () -> "Unexpected traces: " + writer);
    assertEquals(1, spanCount(), () -> "Unexpected spans: " + writer);
    assertAwsSpan(
        receiveSpan,
        expectedService("Sqs", "ReceiveMessage"),
        expectedOperation("Sqs", "ReceiveMessage"),
        "Sqs.ReceiveMessage",
        DDSpanTypes.MESSAGE_CONSUMER,
        Tags.SPAN_KIND_CONSUMER,
        "ReceiveMessage",
        6746998015037429512L,
        expectedQueueUrl(queueName));
    assertEquals(4948377316357291421L, receiveSpan.getTraceId().toLong());
  }

  private static void assertTestSpan(DDSpan span, long parentId) {
    assertNotNull(span);
    assertEquals(parentId, span.getParentId());
    assertFalse(span.isError());
  }

  private static void assertAwsSpan(
      DDSpan span,
      String serviceName,
      String operationName,
      String resourceName,
      String spanType,
      String spanKind,
      String awsOperation,
      long parentId,
      String queueUrl) {
    assertNotNull(span);
    assertEquals(serviceName, span.getServiceName());
    assertEquals(operationName, span.getOperationName().toString());
    assertEquals(resourceName, span.getResourceName().toString());
    assertEquals(spanType, span.getSpanType());
    assertEquals(parentId, span.getParentId());
    assertFalse(span.isError());
    assertEquals("java-aws-sdk", tagValue(span, Tags.COMPONENT));
    assertEquals(spanKind, tagValue(span, Tags.SPAN_KIND));
    assertEquals("Sqs", tagValue(span, "aws.service"));
    assertEquals("Sqs", tagValue(span, "aws_service"));
    assertEquals(awsOperation, tagValue(span, "aws.operation"));
    assertEquals("java-aws-sdk", tagValue(span, "aws.agent"));
    assertEquals(queueUrl, tagValue(span, "aws.queue.url"));
    assertEquals(requestId(), tagValue(span, "aws.requestId").trim());
  }

  private static String tagValue(DDSpan span, String tagName) {
    Object value = span.getTag(tagName);
    return value == null ? null : value.toString();
  }

  private static DDSpan findSpanByResource(String resourceName) {
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if (resourceName.equals(span.getResourceName().toString())) {
          return span;
        }
      }
    }
    return null;
  }

  private static int spanCount() {
    int count = 0;
    for (List<DDSpan> trace : writer) {
      count += trace.size();
    }
    return count;
  }

  private static String expectedQueueUrl(String queueName) {
    return endpoint + "/000000000000/" + queueName;
  }

  private static String requestId() {
    return "00000000-0000-0000-0000-000000000000";
  }

  private static String expectedJmsService() {
    String service =
        SpanNaming.instance()
            .namingSchema()
            .messaging()
            .inboundService("jms", Config.get().isJmsLegacyTracingEnabled())
            .get();
    return service == null ? Config.get().getServiceName() : service;
  }

  private static String expectedJmsOperation() {
    return SpanNaming.instance().namingSchema().messaging().inboundOperation("jms");
  }
}

@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v0")
abstract class SqsClientV0TestBase extends SqsClientTestBase {
  @Override
  String expectedOperation(String awsService, String awsOperation) {
    return "aws.http";
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    if ("Sqs".equals(awsService)) {
      return "sqs";
    }
    return "java-aws-sdk";
  }
}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "false")
class SqsClientV0Test extends SqsClientV0TestBase {}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "true")
class SqsClientV0DataStreamsTest extends SqsClientV0TestBase {
  @Override
  boolean isDataStreamsEnabled() {
    return true;
  }
}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "false")
@WithConfig(key = TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, value = "false")
class SqsClientV0ContextSwapForkedTest extends SqsClientV0TestBase {}

@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v1")
abstract class SqsClientV1TestBase extends SqsClientTestBase {
  @Override
  String expectedOperation(String awsService, String awsOperation) {
    if ("Sqs".equals(awsService)) {
      if ("ReceiveMessage".equals(awsOperation)) {
        return "aws.sqs.process";
      } else if ("SendMessage".equals(awsOperation)) {
        return "aws.sqs.send";
      }
    }
    return "aws." + awsService.toLowerCase(Locale.ROOT) + ".request";
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    return "A-service";
  }
}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "false")
class SqsClientV1ForkedTest extends SqsClientV1TestBase {}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "true")
class SqsClientV1DataStreamsForkedTest extends SqsClientV1TestBase {
  @Override
  boolean isDataStreamsEnabled() {
    return true;
  }
}

@WithConfig(key = GeneralConfig.SERVICE_NAME, value = "A-service")
@WithConfig(key = TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, value = "1")
abstract class SqsClientReceiveIterationTestBase extends AbstractInstrumentationTest {
  private static final AnonymousCredentialsProvider CREDENTIALS_PROVIDER =
      AnonymousCredentialsProvider.create();
  private static SQSRestServer server;
  private static URI endpoint;

  @BeforeAll
  static void startSqsServer() {
    server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start();
    InetSocketAddress address = server.waitUntilStarted().localAddress();
    endpoint = URI.create("http://localhost:" + address.getPort());
  }

  @AfterAll
  static void stopSqsServer() {
    if (server != null) {
      server.stopAndWait();
      server = null;
    }
  }

  @BeforeEach
  void setUpAwsCredentials() {
    if (closePreviousBeforeTest()) {
      AgentTracer.closePrevious(true);
    }
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key");
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key");
  }

  @Test
  void syncReceiveActivatesConsumerSpanForUserHandlerIteration() throws Exception {
    try (SqsClient client =
        SqsClient.builder()
            .region(Region.EU_CENTRAL_1)
            .endpointOverride(endpoint)
            .credentialsProvider(CREDENTIALS_PROVIDER)
            .build()) {
      String queueUrl =
          client
              .createQueue(CreateQueueRequest.builder().queueName("somequeue").build())
              .queueUrl();
      writer.clear();

      TraceUtils.runUnderTrace(
          "parent",
          () -> {
            client.sendMessage(
                SendMessageRequest.builder().queueUrl(queueUrl).messageBody("sometext").build());
            return null;
          });

      // The sync AWS SDK receive pipeline rebuilds the immutable response before user code sees it.
      // Iterating the copied message list must still activate the consumer span for user handlers.
      List<Message> messages =
          client
              .receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build())
              .messages();
      messages.forEach(message -> TraceUtils.runUnderTrace("handler", () -> null));

      assertEquals(1, messages.size());
      writer.waitForTraces(2);

      DDSpan parentSpan = findSpanByResource("parent");
      DDSpan sendSpan = findSpanByResource("Sqs.SendMessage");
      DDSpan receiveSpan = findSpanByResource("Sqs.ReceiveMessage");
      DDSpan handlerSpan = findSpanByResource("handler");

      assertEquals(2, writer.size(), () -> "Unexpected traces: " + writer);
      assertEquals(4, spanCount(), () -> "Unexpected spans: " + writer);

      assertTestSpan(parentSpan, 0);
      assertAwsSpan(
          sendSpan,
          expectedService("Sqs", "SendMessage"),
          expectedOperation("Sqs", "SendMessage"),
          "Sqs.SendMessage",
          DDSpanTypes.HTTP_CLIENT,
          Tags.SPAN_KIND_CLIENT,
          "SendMessage",
          parentSpan.getSpanId());
      assertEquals(parentSpan.getTraceId(), sendSpan.getTraceId());

      assertAwsSpan(
          receiveSpan,
          expectedService("Sqs", "ReceiveMessage"),
          expectedOperation("Sqs", "ReceiveMessage"),
          "Sqs.ReceiveMessage",
          DDSpanTypes.MESSAGE_CONSUMER,
          Tags.SPAN_KIND_CONSUMER,
          "ReceiveMessage",
          sendSpan.getSpanId());
      assertEquals(sendSpan.getTraceId(), receiveSpan.getTraceId());

      assertTestSpan(handlerSpan, receiveSpan.getSpanId());
      assertEquals(receiveSpan.getTraceId(), handlerSpan.getTraceId());
    }
  }

  abstract String expectedOperation(String awsService, String awsOperation);

  abstract String expectedService(String awsService, String awsOperation);

  boolean closePreviousBeforeTest() {
    return true;
  }

  private static void assertTestSpan(DDSpan span, long parentId) {
    assertNotNull(span);
    assertEquals(parentId, span.getParentId());
    assertFalse(span.isError());
  }

  private static void assertAwsSpan(
      DDSpan span,
      String serviceName,
      String operationName,
      String resourceName,
      String spanType,
      String spanKind,
      String awsOperation,
      long parentId) {
    assertNotNull(span);
    assertEquals(serviceName, span.getServiceName());
    assertEquals(operationName, span.getOperationName().toString());
    assertEquals(resourceName, span.getResourceName().toString());
    assertEquals(spanType, span.getSpanType());
    assertEquals(parentId, span.getParentId());
    assertFalse(span.isError());
    assertEquals("java-aws-sdk", tagValue(span, Tags.COMPONENT));
    assertEquals(spanKind, tagValue(span, Tags.SPAN_KIND));
    assertEquals("Sqs", tagValue(span, "aws.service"));
    assertEquals("Sqs", tagValue(span, "aws_service"));
    assertEquals(awsOperation, tagValue(span, "aws.operation"));
    assertEquals("java-aws-sdk", tagValue(span, "aws.agent"));
    assertEquals(expectedQueueUrl(), tagValue(span, "aws.queue.url"));
    assertEquals("00000000-0000-0000-0000-000000000000", tagValue(span, "aws.requestId"));
  }

  private static String tagValue(DDSpan span, String tagName) {
    Object value = span.getTag(tagName);
    return value == null ? null : value.toString();
  }

  private static DDSpan findSpanByResource(String resourceName) {
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if (resourceName.equals(span.getResourceName().toString())) {
          return span;
        }
      }
    }
    return null;
  }

  private static int spanCount() {
    int count = 0;
    for (List<DDSpan> trace : writer) {
      count += trace.size();
    }
    return count;
  }

  private static String expectedQueueUrl() {
    return endpoint + "/000000000000/somequeue";
  }
}

@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v0")
abstract class SqsClientV0ReceiveIterationTestBase extends SqsClientReceiveIterationTestBase {
  @Override
  String expectedOperation(String awsService, String awsOperation) {
    return "aws.http";
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    if ("Sqs".equals(awsService)) {
      return "sqs";
    }
    return "java-aws-sdk";
  }
}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "false")
class SqsClientV0ReceiveIterationTest extends SqsClientV0ReceiveIterationTestBase {}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "true")
class SqsClientV0DataStreamsReceiveIterationTest extends SqsClientV0ReceiveIterationTestBase {}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "false")
@WithConfig(key = TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, value = "false")
class SqsClientV0ContextSwapReceiveIterationForkedTest extends SqsClientV0ReceiveIterationTestBase {
  @Override
  boolean closePreviousBeforeTest() {
    return false;
  }
}

@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v1")
abstract class SqsClientV1ReceiveIterationTestBase extends SqsClientReceiveIterationTestBase {
  @Override
  String expectedOperation(String awsService, String awsOperation) {
    if ("Sqs".equals(awsService)) {
      if ("ReceiveMessage".equals(awsOperation)) {
        return "aws.sqs.process";
      } else if ("SendMessage".equals(awsOperation)) {
        return "aws.sqs.send";
      }
    }
    return "aws." + awsService.toLowerCase(Locale.ROOT) + ".request";
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    return "A-service";
  }
}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "false")
class SqsClientV1ReceiveIterationForkedTest extends SqsClientV1ReceiveIterationTestBase {}

@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "true")
class SqsClientV1DataStreamsReceiveIterationForkedTest
    extends SqsClientV1ReceiveIterationTestBase {}
