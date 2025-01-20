package datadog.trace.instrumentation.googlepubsub;

import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.ContextVisitor;
import java.util.Map;

public class TextMapExtractAdapter implements ContextVisitor<PubsubMessage> {
  public static final TextMapExtractAdapter GETTER = new TextMapExtractAdapter();

  @Override
  public void forEachKey(PubsubMessage carrier, AgentPropagation.KeyClassifier classifier) {
    for (Map.Entry<String, String> kv : carrier.getAttributesMap().entrySet()) {
      String value = kv.getValue();
      if (null != value) {
        if (!classifier.accept(kv.getKey(), value)) {
          return;
        }
      }
    }
  }
}
