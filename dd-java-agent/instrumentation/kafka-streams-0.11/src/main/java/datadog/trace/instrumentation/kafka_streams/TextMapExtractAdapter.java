package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class TextMapExtractAdapter implements AgentPropagation.ContextVisitor<Headers> {

  public static final TextMapExtractAdapter GETTER = new TextMapExtractAdapter();

  @Override
  public void forEachKey(
      Headers carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (Header header : carrier) {
      String lowerCaseKey = header.key().toLowerCase();
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        byte[] value = header.value();
        if (null != value) {
          if (!consumer.accept(
              classification, lowerCaseKey, new String(header.value(), StandardCharsets.UTF_8))) {
            return;
          }
        }
      }
    }
  }
}
