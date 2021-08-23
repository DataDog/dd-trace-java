package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class StampedRecordContextVisitor implements AgentPropagation.ContextVisitor<StampedRecord> {

  public static final StampedRecordContextVisitor SR_GETTER = new StampedRecordContextVisitor();

  @Override
  public void forEachKey(StampedRecord carrier, AgentPropagation.KeyClassifier classifier) {
    for (Header header : carrier.value.headers()) {
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
