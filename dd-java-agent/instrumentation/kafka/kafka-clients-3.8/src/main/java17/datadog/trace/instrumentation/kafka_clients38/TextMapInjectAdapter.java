package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.context.propagation.AsciiValueBuilder;
import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Headers;

public class TextMapInjectAdapter implements TextMapInjectAdapterInterface {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final Headers headers, final String key, final String value) {
    replace(headers, key, value.getBytes(UTF_8));
  }

  @Override
  public void setAscii(final Headers headers, final String key, final AsciiValueBuilder value) {
    // The header takes the built bytes as they are, with no String in between
    replace(headers, key, value.toBytes());
  }

  @Override
  public void setAscii(final Headers headers, final String key, final byte[] value) {
    replace(headers, key, value);
  }

  private static void replace(Headers headers, String key, byte[] value) {
    // Codecs can write the same key, so replace it, but probe first: remove allocates an iterator,
    // lastHeader doesn't, and the key is usually not there yet
    if (headers.lastHeader(key) != null) {
      headers.remove(key);
    }
    headers.add(key, value);
  }

  public void injectTimeInQueue(Headers headers) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(System.currentTimeMillis());
    headers.add(KafkaDecorator.KAFKA_PRODUCED_KEY, buf.array());
  }
}
