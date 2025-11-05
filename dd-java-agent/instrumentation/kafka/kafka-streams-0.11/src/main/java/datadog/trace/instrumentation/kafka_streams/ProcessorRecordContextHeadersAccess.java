package datadog.trace.instrumentation.kafka_streams;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessorRecordContextHeadersAccess {
  private static final Logger log =
      LoggerFactory.getLogger(ProcessorRecordContextHeadersAccess.class);

  public static final MethodHandle HEADERS_METHOD;

  static {
    MethodHandle method;
    try {
      method =
          MethodHandles.publicLookup()
              .findVirtual(
                  ProcessorRecordContext.class, "headers", MethodType.methodType(Headers.class));
    } catch (Throwable e) {
      log.debug("Exception loading MethodHandle", e);
      method = null;
    }
    HEADERS_METHOD = method;
  }
}
