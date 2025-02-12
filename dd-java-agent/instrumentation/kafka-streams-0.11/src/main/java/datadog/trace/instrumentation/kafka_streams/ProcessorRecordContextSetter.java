package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextHeadersAccess.HEADERS_METHOD;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.context.propagation.CarrierSetter;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessorRecordContextSetter implements CarrierSetter<ProcessorRecordContext> {
  private static final Logger log = LoggerFactory.getLogger(ProcessorRecordContextSetter.class);

  public static final ProcessorRecordContextSetter PR_SETTER = new ProcessorRecordContextSetter();

  @Override
  public void set(ProcessorRecordContext carrier, String key, String value) {
    if (HEADERS_METHOD == null) {
      return;
    }
    try {
      Headers headers = (Headers) HEADERS_METHOD.invokeExact(carrier);
      byte[] bytes = value.getBytes(UTF_8);
      headers.remove(key).add(key, bytes);
    } catch (Throwable e) {
      log.debug("Unable to set value", e);
    }
  }
}
