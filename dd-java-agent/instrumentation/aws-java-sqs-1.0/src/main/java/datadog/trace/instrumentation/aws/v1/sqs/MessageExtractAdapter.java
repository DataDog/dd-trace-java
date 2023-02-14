package datadog.trace.instrumentation.aws.v1.sqs;

import com.amazonaws.services.sqs.model.Message;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageExtractAdapter.class);

  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    for (Map.Entry<String, String> entry : carrier.getAttributes().entrySet()) {
      String key = entry.getKey();
      if ("AWSTraceHeader".equalsIgnoreCase(key)) {
        key = "X-Amzn-Trace-Id";
      }
      if (!classifier.accept(key, entry.getValue())) {
        return;
      }
    }
  }

  public long extractTimeInQueueStart(final Message carrier) {
    try {
      Map<String, String> attributes = carrier.getAttributes();
      if (attributes.containsKey("SentTimestamp")) {
        return Long.parseLong(attributes.get("SentTimestamp"));
      }
    } catch (Exception e) {
      log.debug("Unable to get SQS sent time", e);
    }
    return 0;
  }
}
