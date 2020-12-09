package datadog.trace.instrumentation.kafka_clients;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class TextMapExtractAdapter implements AgentPropagation.ContextVisitor<Headers> {

  public static final TextMapExtractAdapter GETTER =
      new TextMapExtractAdapter(Config.get().isKafkaClientBase64DecodingEnabled());

  private final Base64Decoder base64;

  public TextMapExtractAdapter(boolean base64DecodeHeaders) {
    this.base64 = base64DecodeHeaders ? new Base64Decoder() : null;
  }

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    for (Header header : carrier) {
      String key = header.key();
      byte[] value = header.value();
      if (null != value) {
        String string =
            base64 != null
                ? new String(base64.decode(header.value()), UTF_8)
                : new String(header.value(), UTF_8);
        if (!classifier.accept(key, string)) {
          return;
        }
      }
    }
  }
}
