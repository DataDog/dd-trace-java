package datadog.trace.instrumentation.aws.v1.sqs;

import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.messaging.DatadogAttributeParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageExtractAdapter.class);
  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  public static final boolean SHOULD_EXTRACT_CONTEXT_FROM_BODY =
      Config.get().isSqsBodyPropagationEnabled();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> systemAttributes = carrier.getAttributes();
    if (systemAttributes.containsKey("AWSTraceHeader")) {
      // alias 'AWSTraceHeader' to 'X-Amzn-Trace-Id' because it uses the same format
      classifier.accept("X-Amzn-Trace-Id", systemAttributes.get("AWSTraceHeader"));
    }
    Map<String, MessageAttributeValue> messageAttributes = carrier.getMessageAttributes();
    if (messageAttributes.containsKey("_datadog")) {
      MessageAttributeValue datadog = messageAttributes.get("_datadog");
      if ("String".equals(datadog.getDataType())) {
        DatadogAttributeParser.forEachProperty(classifier, datadog.getStringValue());
      } else if ("Binary".equals(datadog.getDataType())) {
        DatadogAttributeParser.forEachProperty(classifier, datadog.getBinaryValue());
      }
    } else if (SHOULD_EXTRACT_CONTEXT_FROM_BODY) {
      try {
        this.forEachKeyInBody(carrier.getBody(), classifier);
      } catch (Throwable e) {
        log.debug("Error extracting Datadog context from SQS message body", e);
      }
    }
  }

  public void forEachKeyInBody(String body, AgentPropagation.KeyClassifier classifier)
      throws IOException {
    // Parse the JSON string into a JsonNode
    JsonNode rootNode = MAPPER.readTree(body);

    // Navigate to MessageAttributes._datadog
    JsonNode messageAttributes = rootNode.path("MessageAttributes").path("_datadog");

    // Extract Value and Type
    String value = messageAttributes.path("Value").asText();
    String type = messageAttributes.path("Type").asText();
    if ("String".equals(type)) {
      DatadogAttributeParser.forEachProperty(classifier, value);
    } else if ("Binary".equals(type)) {
      ByteBuffer decodedValue = ByteBuffer.wrap(Base64.getDecoder().decode(value));
      DatadogAttributeParser.forEachProperty(classifier, decodedValue);
    }
  }

  public long extractTimeInQueueStart(final Message carrier) {
    try {
      Map<String, String> systemAttributes = carrier.getAttributes();
      if (systemAttributes.containsKey("SentTimestamp")) {
        return Long.parseLong(systemAttributes.get("SentTimestamp"));
      }
    } catch (Exception e) {
      log.debug("Unable to get SQS sent time", e);
    }
    return 0;
  }
}
