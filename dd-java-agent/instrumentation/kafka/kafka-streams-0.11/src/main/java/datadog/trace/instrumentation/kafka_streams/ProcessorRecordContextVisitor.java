package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_PRODUCED_KEY;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextHeadersAccess.HEADERS_METHOD;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.ContextVisitor;
import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessorRecordContextVisitor implements ContextVisitor<ProcessorRecordContext> {
  private static final Logger log = LoggerFactory.getLogger(ProcessorRecordContextVisitor.class);

  public static final ProcessorRecordContextVisitor PR_GETTER = new ProcessorRecordContextVisitor();

  @Override
  public void forEachKey(
      ProcessorRecordContext carrier, AgentPropagation.KeyClassifier classifier) {
    if (HEADERS_METHOD == null) {
      return;
    }
    try {
      Headers headers = (Headers) HEADERS_METHOD.invokeExact(carrier);
      for (Header header : headers) {
        String key = header.key();
        byte[] value = header.value();
        if (null != value) {
          if (!classifier.accept(key, new String(header.value(), UTF_8))) {
            return;
          }
        }
      }
    } catch (Throwable ex) {
      log.debug("Exception getting headers", ex);
    }
  }

  public long extractTimeInQueueStart(ProcessorRecordContext carrier) {
    if (HEADERS_METHOD == null) {
      return 0;
    }
    try {
      Headers headers = (Headers) HEADERS_METHOD.invokeExact(carrier);
      Header header = headers.lastHeader(KAFKA_PRODUCED_KEY);
      if (null != header) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put(header.value());
        buf.flip();
        return buf.getLong();
      }
    } catch (Throwable e) {
      log.debug("Unable to get kafka produced time", e);
    }
    return 0;
  }
}
