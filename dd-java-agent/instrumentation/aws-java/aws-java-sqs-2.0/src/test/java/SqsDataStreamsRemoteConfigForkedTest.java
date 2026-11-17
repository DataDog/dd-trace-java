import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.DataStreamsRemoteConfigTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.net.URI;
import java.util.List;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
@WithConfig(key = "data.streams.enabled", value = "false")
class SqsDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String QUEUE = "dsmremoteconfig";
  private static final String PATHWAY_KEY = "dd-pathway-ctx-base64";

  private static DataStreamsRemoteConfigTracer dataStreams;

  private static SQSRestServer server;
  private static URI endpoint;

  /** Installs a tracer with a recording DSM, since AbstractInstrumentationTest has none. */
  @BeforeAll
  static void installDataStreamsTracer() {
    dataStreams = DataStreamsRemoteConfigTracer.install(tracer, writer);
    tracer = dataStreams.tracer();
  }

  @BeforeAll
  static void startSqsServer() {
    server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start();
    int port = server.waitUntilStarted().localAddress().getPort();
    endpoint = URI.create("http://localhost:" + port);
  }

  @AfterAll
  static void stopSqsServer() {
    if (server != null) {
      server.stopAndWait();
    }
  }

  @BeforeEach
  void clearDataStreams() {
    dataStreams.clear();
  }

  @AfterEach
  void disableDataStreams() throws Exception {
    dataStreams.toggleDataStreams(false);
  }

  @Test
  void dataStreamsFollowsTheLiveConfigForClientsBuiltWhileItWasOff() throws Exception {
    try (SqsClient client =
        SqsClient.builder()
            .region(Region.EU_CENTRAL_1)
            .endpointOverride(endpoint)
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .build()) {
      String queueUrl =
          client.createQueue(CreateQueueRequest.builder().queueName(QUEUE).build()).queueUrl();

      // data streams is off
      assertFalse(sendAndReceive(client, queueUrl, "off").contains(PATHWAY_KEY));
      assertEquals(0, dataStreams.settledGroupCount());

      // remote config enables data streams
      dataStreams.toggleDataStreams(true);
      assertTrue(sendAndReceive(client, queueUrl, "on").contains(PATHWAY_KEY));
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(dataStreams.hasGroup("direction:out", "topic:" + QUEUE, "type:sqs"));
            assertTrue(dataStreams.hasGroup("direction:in", "topic:" + QUEUE, "type:sqs"));
          });

      // remote config disables data streams again
      dataStreams.toggleDataStreams(false);
      int groupsBeforeOffAgain = dataStreams.settledGroupCount();
      assertFalse(sendAndReceive(client, queueUrl, "off again").contains(PATHWAY_KEY));
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, dataStreams.settledGroupCount());
    }
  }

  /** Sends one message and returns its received {@code _datadog} attribute value. */
  private static String sendAndReceive(SqsClient client, String queueUrl, String body) {
    client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
    List<Message> messages =
        client
            .receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build())
            .messages();
    // iterating finishes the consume span and sets the inbound checkpoint
    Message received = null;
    for (Message message : messages) {
      received = message;
    }
    assertNotNull(received);
    assertEquals(body, received.body());
    client.deleteMessage(
        DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(received.receiptHandle())
            .build());
    MessageAttributeValue datadogAttribute = received.messageAttributes().get("_datadog");
    assertNotNull(datadogAttribute);
    return datadogAttribute.stringValue();
  }
}
