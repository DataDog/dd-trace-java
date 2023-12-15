package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class StampedRecordContextSetter implements AgentPropagation.BinarySetter<StampedRecord> {

  public static final StampedRecordContextSetter SR_SETTER = new StampedRecordContextSetter();

  @Override
  public void set(StampedRecord carrier, String key, String value) {
    set(carrier, key, value.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void set(StampedRecord carrier, String key, byte[] value) {
    Headers headers = carrier.value.headers();
    if (headers != null) {
      headers.remove(key).add(key, value);
    }
  }
}
