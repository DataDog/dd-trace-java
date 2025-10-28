package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_PRODUCED_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.ContextVisitor;
import java.nio.ByteBuffer;
import java.util.Base64;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TextMapExtractAdapter implements ContextVisitor<Headers> {
  private static final Logger log = LoggerFactory.getLogger(TextMapExtractAdapter.class);

  public static final TextMapExtractAdapter GETTER =
      new TextMapExtractAdapter(Config.get().isKafkaClientBase64DecodingEnabled());

  private final Base64.Decoder base64;

  public TextMapExtractAdapter(boolean base64DecodeHeaders) {
    this.base64 = base64DecodeHeaders ? Base64.getDecoder() : null;
  }

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    for (Header header : carrier) {
      String key = header.key();
      byte[] value = header.value();
      if (null == value) {
        continue;
      }
      if (base64 != null) {
        value = base64.decode(value);
      }
      if (!classifier.accept(key, new String(value, UTF_8))) {
        return;
      }
    }
  }

  public long extractTimeInQueueStart(Headers carrier) {
    Header header = carrier.lastHeader(KAFKA_PRODUCED_KEY);
    if (null != header) {
      try {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put(base64 != null ? base64.decode(header.value()) : header.value());
        buf.flip();
        return buf.getLong();
      } catch (Exception e) {
        log.debug("Unable to get kafka produced time", e);
      }
    }
    return 0;
  }
}
