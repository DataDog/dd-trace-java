package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import java.util.Map;

public class TextMapExtractAdapter extends CachingContextVisitor<Map<String, Object>> {

  public static final TextMapExtractAdapter GETTER = new TextMapExtractAdapter();

  @Override
  public void forEachKey(
      Map<String, Object> carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (Map.Entry<String, Object> entry : carrier.entrySet()) {
      String lowerCaseKey = toLowerCase(entry.getKey());
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(
            classification,
            lowerCaseKey,
            null == entry.getValue() ? null : String.valueOf(entry.getValue()))) {
          return;
        }
      }
    }
  }
}
