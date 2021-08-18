package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessorRecordContextVisitor
    implements AgentPropagation.ContextVisitor<ProcessorRecordContext> {
  private static final Logger log = LoggerFactory.getLogger(ProcessorRecordContextVisitor.class);

  // Using a method handle here to avoid forking the instrumentation for versions 2.7+
  private static final MethodHandle HEADERS_METHOD;

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
          if (!classifier.accept(key, new String(header.value(), StandardCharsets.UTF_8))) {
            return;
          }
        }
      }
    } catch (Throwable ex) {
      log.debug("Exception getting headers", ex);
    }
  }
}
