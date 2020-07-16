package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class TextMapExtractAdapter extends CachingContextVisitor<Headers> {

  public static final TextMapExtractAdapter GETTER = new TextMapExtractAdapter();

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    for (Header header : carrier) {
      String lowerCaseKey = toLowerCase(header.key());
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        byte[] value = header.value();
        if (null != value) {
          if (!classifier.accept(
              classification, lowerCaseKey, new String(header.value(), StandardCharsets.UTF_8))) {
            return;
          }
        }
      }
    }
  }
}
