package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.messaging.DatadogAttributeParser;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageExtractAdapter.class);

  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> systemAttributes = carrier.attributesAsStrings();
    if (systemAttributes.containsKey("AWSTraceHeader")) {
      // alias 'AWSTraceHeader' to 'X-Amzn-Trace-Id' because it uses the same format
      classifier.accept("X-Amzn-Trace-Id", systemAttributes.get("AWSTraceHeader"));
    }
    Map<String, MessageAttributeValue> messageAttributes = carrier.messageAttributes();
    if (messageAttributes.containsKey("_datadog")) {
      MessageAttributeValue datadog = messageAttributes.get("_datadog");
      if ("String".equals(datadog.dataType())) {
        DatadogAttributeParser.forEachProperty(classifier, datadog.stringValue());
      } else if ("Binary".equals(datadog.dataType()) && null != datadog.binaryValue()) {
        DatadogAttributeParser.forEachProperty(classifier, datadog.binaryValue().asByteBuffer());
      }
    }
  }

  public long extractTimeInQueueStart(final Message carrier) {
    try {
      Map<String, String> systemAttributes = carrier.attributesAsStrings();
      if (systemAttributes.containsKey("SentTimestamp")) {
        return Long.parseLong(systemAttributes.get("SentTimestamp"));
      }
    } catch (Exception e) {
      log.debug("Unable to get SQS sent time", e);
    }
    return 0;
  }
}
