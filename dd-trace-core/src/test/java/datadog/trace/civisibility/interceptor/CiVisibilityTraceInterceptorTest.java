package datadog.trace.civisibility.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class CiVisibilityTraceInterceptorTest extends DDCoreSpecification {

  private ListWriter writer = new ListWriter();
  private CoreTracer tracer = tracerBuilder().writer(writer).build();

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void discardATraceThatDoesNotComeFromCiApp() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);
    tracer.buildSpan("sample-span").start().finish();

    assertEquals(0, writer.size());
  }

  @Test
  void doNotDiscardATraceThatComesFromCiApp() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);

    AgentSpan span = tracer.buildSpan("sample-span").start();
    ((DDSpanContext) span.context()).setOrigin(CIConstants.CIAPP_TEST_ORIGIN);
    span.finish();

    assertEquals(1, writer.size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        DDSpanTypes.TEST,
        DDSpanTypes.TEST_SUITE_END,
        DDSpanTypes.TEST_MODULE_END,
        DDSpanTypes.TEST_SESSION_END
      })
  void addTracerVersionToSpansOfType(String spanType) throws Exception {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);

    AgentSpan span = tracer.buildSpan("sample-span").withSpanType(spanType).start();
    ((DDSpanContext) span.context()).setOrigin(CIConstants.CIAPP_TEST_ORIGIN);
    span.finish();
    writer.waitForTraces(1);

    List trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan receivedSpan = (DDSpan) trace.get(0);
    assertNotNull(receivedSpan.getTag(DDTags.LIBRARY_VERSION_TAG_KEY));
  }
}
