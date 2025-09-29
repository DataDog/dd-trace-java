package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_PRODUCED_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.ContextVisitor;
import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.streams.processor.internals.StampedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StampedRecordContextVisitor implements ContextVisitor<StampedRecord> {

  private static final Logger log = LoggerFactory.getLogger(StampedRecordContextVisitor.class);

  public static final StampedRecordContextVisitor SR_GETTER = new StampedRecordContextVisitor();

  @Override
  public void forEachKey(StampedRecord carrier, AgentPropagation.KeyClassifier classifier) {
    for (Header header : carrier.value.headers()) {
      String key = header.key();
      byte[] value = header.value();
      if (null != value) {
        if (!classifier.accept(key, new String(header.value(), UTF_8))) {
          return;
        }
      }
    }
  }

  public long extractTimeInQueueStart(StampedRecord carrier) {
    try {
      Header header = carrier.value.headers().lastHeader(KAFKA_PRODUCED_KEY);
      if (null != header) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put(header.value());
        buf.flip();
        return buf.getLong();
      }
    } catch (Exception e) {
      log.debug("Unable to get kafka produced time", e);
    }
    return 0;
  }
}
