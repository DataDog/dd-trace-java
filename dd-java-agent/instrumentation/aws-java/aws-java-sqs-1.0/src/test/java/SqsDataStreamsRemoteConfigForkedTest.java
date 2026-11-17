import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.DataStreamsRemoteConfigTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.util.List;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
@WithConfig(key = "data.streams.enabled", value = "false")
class SqsDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String QUEUE = "dsmremoteconfig";
  private static final String PATHWAY_KEY = "dd-pathway-ctx-base64";

  private static DataStreamsRemoteConfigTracer dataStreams;

  private static SQSRestServer server;
  private static AwsClientBuilder.EndpointConfiguration endpoint;

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
    endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:" + port, "elasticmq");
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
    AmazonSQS client =
        AmazonSQSClientBuilder.standard()
            .withEndpointConfiguration(endpoint)
            .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
            .build();
    try {
      String queueUrl = client.createQueue(QUEUE).getQueueUrl();

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
    } finally {
      client.shutdown();
    }
  }

  /** Sends one message and returns its received {@code _datadog} attribute value. */
  private static String sendAndReceive(AmazonSQS client, String queueUrl, String body) {
    client.sendMessage(queueUrl, body);
    List<Message> messages =
        client.receiveMessage(new ReceiveMessageRequest(queueUrl)).getMessages();
    // iterating finishes the consume span and sets the inbound checkpoint
    Message received = null;
    for (Message message : messages) {
      received = message;
    }
    assertNotNull(received);
    assertEquals(body, received.getBody());
    client.deleteMessage(queueUrl, received.getReceiptHandle());
    MessageAttributeValue datadogAttribute = received.getMessageAttributes().get("_datadog");
    assertNotNull(datadogAttribute);
    return datadogAttribute.getStringValue();
  }
}
