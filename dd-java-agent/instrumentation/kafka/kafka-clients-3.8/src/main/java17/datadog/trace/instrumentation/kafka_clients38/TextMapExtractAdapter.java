package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.api.Functions.BASE64_DECODE;
import static datadog.trace.api.Functions.UTF8_BYTES_TO_STRING;
import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.instrumentation.kafka_common.Utils.readProducedTime;

import datadog.context.propagation.CarrierKeys;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.ContextVisitor;
import datadog.trace.bootstrap.instrumentation.api.BufferedHeaderFields;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TextMapExtractAdapter implements ContextVisitor<Headers> {
  private static final Logger log = LoggerFactory.getLogger(TextMapExtractAdapter.class);

  public static final TextMapExtractAdapter GETTER =
      new TextMapExtractAdapter(Config.get().isKafkaClientBase64DecodingEnabled());

  private final Function<byte[], String> headerValueTransformer;
  private final Base64.Decoder decoder;

  public TextMapExtractAdapter(boolean decodeBase64Headers) {
    if (decodeBase64Headers) {
      this.headerValueTransformer = BASE64_DECODE;
      this.decoder = Base64.getDecoder();
    } else {
      this.headerValueTransformer = UTF8_BYTES_TO_STRING;
      this.decoder = null;
    }
  }

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    visit(carrier, CarrierKeys.all(), classifier);
  }

  /**
   * Visits only the headers whose key is in {@code keys}. A skipped header's value is never read,
   * and a fetched header's key is matched from its bytes, so a header nothing reads (an
   * application's own) costs neither a key decode nor a value copy.
   */
  @Override
  public void forEachKeyValue(
      Headers carrier, CarrierKeys keys, BiConsumer<String, String> visitor) {
    visit(
        carrier,
        keys,
        (key, value) -> {
          visitor.accept(key, value);
          return true;
        });
  }

  /**
   * Visits the headers whose key is in {@code keys}, until {@code sink} returns false. A header the
   * agent gave {@link BufferedHeaderFields} is read through {@link RecordHeaderAccess}, so known
   * keys aren't decoded and UTF-8 values aren't copied before decoding; any other goes through
   * {@code key()} and {@code value()}.
   */
  private void visit(Headers carrier, CarrierKeys keys, AgentPropagation.KeyClassifier sink) {
    for (Header header : carrier) {
      BufferedHeaderFields fields =
          header instanceof BufferedHeaderFields ? (BufferedHeaderFields) header : null;
      String key = key(header, fields, keys);
      if (key == null) {
        continue;
      }
      String value = value(header, fields, key);
      if (value == null) {
        continue;
      }
      if (!sink.accept(key, value)) {
        return;
      }
    }
  }

  /**
   * The header's key, or null if it isn't in {@code keys}. A fetched header's key is matched from
   * its bytes once: a declared name comes back as that name's (lower-cased) constant, a prefix
   * match is decoded, and anything else is skipped undecoded.
   */
  private static String key(Header header, BufferedHeaderFields fields, CarrierKeys keys) {
    if (fields != null && fields.$$DD$$__key() == null) {
      ByteBuffer keyBuffer = fields.$$DD$$__keyBuffer();
      if (keyBuffer != null) {
        if (keys.isAll()) {
          return RecordHeaderAccess.key(header, fields);
        }
        String name = keys.nameAscii(keyBuffer);
        if (name != null) {
          return name;
        }
        return keys.hasPrefixAscii(keyBuffer) ? header.key() : null;
      }
    }
    String key = header.key();
    return keys.contains(key) ? key : null;
  }

  /** The header's value decoded, or null if it has none or doesn't decode. */
  private String value(Header header, BufferedHeaderFields fields, String key) {
    if (decoder == null) {
      if (fields != null) {
        return RecordHeaderAccess.utf8Value(header, fields);
      }
      byte[] bytes = header.value();
      return bytes == null ? null : UTF8_BYTES_TO_STRING.apply(bytes);
    }
    byte[] bytes = header.value();
    if (bytes == null) {
      return null;
    }
    String decoded = headerValueTransformer.apply(bytes);
    if (decoded == null) {
      log.debug(EXCLUDE_TELEMETRY, "Failed to Base64-decode Kafka header '{}', skipping", key);
    }
    return decoded;
  }

  public long extractTimeInQueueStart(Headers carrier) {
    Header header = carrier.lastHeader(KafkaDecorator.KAFKA_PRODUCED_KEY);
    if (null != header) {
      try {
        return readProducedTime(decoder != null ? decoder.decode(header.value()) : header.value());
      } catch (Exception e) {
        log.debug(EXCLUDE_TELEMETRY, "Unable to get kafka produced time", e);
      }
    }
    return 0;
  }
}
