package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextHeadersAccess.HEADERS_METHOD;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessorRecordContextSetter
    implements AgentPropagation.BinarySetter<ProcessorRecordContext> {
  private static final Logger log = LoggerFactory.getLogger(ProcessorRecordContextSetter.class);

  public static final ProcessorRecordContextSetter PR_SETTER = new ProcessorRecordContextSetter();

  @Override
  public void set(ProcessorRecordContext carrier, String key, String value) {
    set(carrier, key, value.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void set(ProcessorRecordContext carrier, String key, byte[] value) {
    if (HEADERS_METHOD == null) {
      return;
    }
    try {
      Headers headers = (Headers) HEADERS_METHOD.invokeExact(carrier);
      headers.remove(key).add(key, value);
    } catch (Throwable e) {
      log.debug("Unable to set value", e);
    }
  }
}
