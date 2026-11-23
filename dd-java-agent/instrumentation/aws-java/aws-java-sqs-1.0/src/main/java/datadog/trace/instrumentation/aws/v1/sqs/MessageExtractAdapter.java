package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;

import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.messaging.DatadogAttributeParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
    boolean shouldExtractContextFromBody = SHOULD_EXTRACT_CONTEXT_FROM_BODY;
    Map<String, String> systemAttributes = carrier.getAttributes();
    if (systemAttributes.containsKey("AWSTraceHeader")) {
      // alias 'AWSTraceHeader' to 'X-Amzn-Trace-Id' because it uses the same format
      classifier.accept("X-Amzn-Trace-Id", systemAttributes.get("AWSTraceHeader"));
    }
    Map<String, MessageAttributeValue> messageAttributes = carrier.getMessageAttributes();
    if (messageAttributes.containsKey("_datadog")) {
      MessageAttributeValue datadog = messageAttributes.get("_datadog");
      boolean hasPathwayContext = false;
      if ("String".equals(datadog.getDataType())) {
        String value = datadog.getStringValue();
        hasPathwayContext = value != null && value.contains(PROPAGATION_KEY_BASE64);
        DatadogAttributeParser.forEachProperty(classifier, value);
      } else if ("Binary".equals(datadog.getDataType())) {
        ByteBuffer value = datadog.getBinaryValue();
        if (value != null) {
          ByteBuffer duplicate = value.duplicate();
          hasPathwayContext =
              StandardCharsets.UTF_8.decode(duplicate).toString().contains(PROPAGATION_KEY_BASE64);
        }
        DatadogAttributeParser.forEachProperty(classifier, value);
      }
      shouldExtractContextFromBody &= !hasPathwayContext;
    }

    if (shouldExtractContextFromBody) {
      // The top-level SQS _datadog attribute and the SNS-style body payload are separate carriers.
      // APM headers may be present in the message attribute while DSM context still only exists in
      // the body payload.
      try {
        this.forEachKeyInBody(carrier.getBody(), classifier);
      } catch (Throwable e) {
        log.debug("Error extracting Datadog context from SQS message body", e);
      }
    }
  }

  public void forEachKeyInBody(String body, AgentPropagation.KeyClassifier classifier)
      throws IOException {
    JsonNode messageAttributes = null;
    try (JsonParser parser = MAPPER.getFactory().createParser(body)) {
      if (parser.nextToken() == JsonToken.START_OBJECT) {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          String name = parser.getCurrentName();
          parser.nextToken();
          if ("MessageAttributes".equals(name)) {
            messageAttributes = readDatadogAttribute(parser);
          } else {
            parser.skipChildren();
          }
        }
      } else {
        MAPPER.readTree(parser);
      }
    }
    if (messageAttributes == null) {
      return;
    }

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

  private static JsonNode readDatadogAttribute(JsonParser parser) throws IOException {
    JsonNode attribute = null;
    if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        String name = parser.getCurrentName();
        parser.nextToken();
        if ("_datadog".equals(name)) {
          attribute = MAPPER.readTree(parser);
        } else {
          parser.skipChildren();
        }
      }
    } else {
      parser.skipChildren();
    }
    return attribute;
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
