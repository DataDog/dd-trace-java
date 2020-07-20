package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class TextMapExtractAdapter implements AgentPropagation.ContextVisitor<Headers> {

  public static final TextMapExtractAdapter GETTER = new TextMapExtractAdapter();

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    for (Header header : carrier) {
      String key = header.key();
      byte[] value = header.value();
      if (null != value) {
        if (!classifier.accept(key, new String(header.value(), StandardCharsets.UTF_8))) {
          return;
        }
      }
    }
  }
}
