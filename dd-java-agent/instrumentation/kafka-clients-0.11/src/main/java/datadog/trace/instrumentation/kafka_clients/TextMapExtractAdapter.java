package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class TextMapExtractAdapter implements AgentPropagation.ContextVisitor<Headers> {

  public static final TextMapExtractAdapter GETTER =
      new TextMapExtractAdapter(Config.get().isKafkaClientBase64DecodingEnabled());

  private final boolean base64DecodeHeaders;
  private final Base64Decoder base64;

  public TextMapExtractAdapter(boolean base64DecodeHeaders) {
    this.base64DecodeHeaders = base64DecodeHeaders;
    this.base64 = base64DecodeHeaders ? new Base64Decoder() : null;
  }

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
          String string =
              base64DecodeHeaders
                  ? new String(base64.decode(header.value()), UTF_8)
                  : new String(header.value(), UTF_8);
          if (!consumer.accept(classification, lowerCaseKey, string)) {
            return;
          }
        }
      }
    }
  }
}
