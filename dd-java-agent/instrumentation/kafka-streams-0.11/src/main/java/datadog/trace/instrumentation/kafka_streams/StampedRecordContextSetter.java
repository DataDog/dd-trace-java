package datadog.trace.instrumentation.kafka_streams;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.Setter;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class StampedRecordContextSetter implements Setter<StampedRecord> {
  public static final StampedRecordContextSetter SR_SETTER = new StampedRecordContextSetter();

  @Override
  public void set(StampedRecord carrier, String key, String value) {
    Headers headers = carrier.value.headers();
    if (headers != null) {
      headers.remove(key).add(key, value.getBytes(UTF_8));
    }
  }
}
