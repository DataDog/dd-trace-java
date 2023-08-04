package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DATADOG_KEY;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageExtractAdapter.class);

  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {

    for (Map.Entry<String, String> entry : carrier.attributesAsStrings().entrySet()) {
      String key = entry.getKey();
      if ("AWSTraceHeader".equalsIgnoreCase(key)) {
        key = "X-Amzn-Trace-Id";
      }
      if (!classifier.accept(key, entry.getValue())) {
        return;
      }
    }

    if (Config.get().isDataStreamsEnabled()) {
      for (Map.Entry<String, MessageAttributeValue> entry :
          carrier.messageAttributes().entrySet()) {
        String key = entry.getKey();
        if (key.equalsIgnoreCase(DATADOG_KEY)) {
          Optional<String> value = entry.getValue().getValueForField("StringValue", String.class);
          if (value.isPresent()) {
            if (!classifier.accept(key, value.get())) {
              return;
            }
          }
        }
      }
    }
  }

  public long extractTimeInQueueStart(final Message carrier) {
    try {
      Map<String, String> attributes = carrier.attributesAsStrings();
      if (attributes.containsKey("SentTimestamp")) {
        return Long.parseLong(attributes.get("SentTimestamp"));
      }
    } catch (Exception e) {
      log.debug("Unable to get SQS sent time", e);
    }
    return 0;
  }
}
