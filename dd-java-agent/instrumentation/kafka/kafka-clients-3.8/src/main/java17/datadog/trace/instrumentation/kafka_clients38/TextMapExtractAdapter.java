package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
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
    Header header = carrier.lastHeader(KafkaDecorator.KAFKA_PRODUCED_KEY);
    if (null != header) {
      try {
        byte[] value = header.value();
        if (base64 != null) {
          long ts = extractBase64Timestamp(value);
          return ts != 0 ? ts : extractBinaryTimestamp(value);
        } else {
          return extractBinaryTimestamp(value);
        }
      } catch (Exception e) {
        log.debug("Unable to get kafka produced time", e);
      }
    }
    return 0;
  }

  private long extractBase64Timestamp(byte[] value) {
    try {
      ByteBuffer buf = ByteBuffer.allocate(8);
      buf.put(base64.decode(value));
      buf.flip();
      return buf.getLong();
    } catch (Exception e) {
      log.debug(EXCLUDE_TELEMETRY, "Unable to extract kafka produced time from base64 header", e);
      return 0;
    }
  }

  private static long extractBinaryTimestamp(byte[] value) {
    try {
      ByteBuffer buf = ByteBuffer.allocate(8);
      buf.put(value);
      buf.flip();
      return buf.getLong();
    } catch (Exception e) {
      log.debug(EXCLUDE_TELEMETRY, "Unable to extract kafka produced time from binary header", e);
      return 0;
    }
  }
}
