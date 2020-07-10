package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class TextMapExtractAdapter implements AgentPropagation.Getter<Headers> {

  public static final TextMapExtractAdapter GETTER =
      new TextMapExtractAdapter(Config.get().isKafkaClientBase64DecodingEnabled());

  private final boolean base64DecodeHeaders;
  private final Base64Decoder base64;

  public TextMapExtractAdapter(boolean base64DecodeHeaders) {
    this.base64DecodeHeaders = base64DecodeHeaders;
    this.base64 = base64DecodeHeaders ? new Base64Decoder() : null;
  }

  @Override
  public Iterable<String> keys(final Headers headers) {
    final List<String> keys = new ArrayList<>();
    for (final Header header : headers) {
      keys.add(header.key());
    }
    return keys;
  }

  @Override
  public String get(final Headers headers, final String key) {
    final Header header = headers.lastHeader(key);
    if (header == null) {
      return null;
    }
    if (header.value() == null) {
      return null;
    }
    if (base64DecodeHeaders) {
      return new String(base64.decode(header.value()), StandardCharsets.UTF_8);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
