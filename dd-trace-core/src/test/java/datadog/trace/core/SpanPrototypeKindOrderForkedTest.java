package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

/**
 * A prototype's span.kind is seeded before its other tags, so interceptors that read the kind see
 * it whatever order the prototype's tags were added in. Here the URL interceptor picks client path
 * mappings only when the kind is client. Forked because the path mappings are read once per JVM.
 */
@WithConfig(
    key = TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
    value = "/users/*:/users/{id}")
class SpanPrototypeKindOrderForkedTest extends DDCoreJavaSpecification {

  @Test
  void aUrlAddedBeforeTheKindStillUsesClientMappings() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SpanPrototype prototype =
          SpanPrototype.builder()
              .initInstrumentationName("test-instr")
              .initOperationName("client.op")
              .initTag(HTTP_URL, "/users/123") // before the kind
              .initKind(SPAN_KIND_CLIENT)
              .build();
      DDSpan span = (DDSpan) tracer.buildSpan(prototype, null).start();
      try {
        assertEquals(SPAN_KIND_CLIENT, span.getSpanKindString());
        assertEquals("/users/{id}", span.getResourceName().toString());
      } finally {
        span.finish();
      }
    } finally {
      tracer.close();
    }
  }
}
