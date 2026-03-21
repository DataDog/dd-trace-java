package datadog.trace.civisibility.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class CiVisibilityApmProtocolInterceptorTest extends DDCoreSpecification {

  private ListWriter writer = new ListWriter();
  private CoreTracer tracer = tracerBuilder().writer(writer).build();

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void testSuiteAndTestModuleSpansAreFilteredOut() throws Exception {
    tracer.addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE);

    tracer.buildSpan("test-module").withSpanType(DDSpanTypes.TEST_MODULE_END).start().finish();
    tracer.buildSpan("test-suite").withSpanType(DDSpanTypes.TEST_SUITE_END).start().finish();
    tracer.buildSpan("test").withSpanType(DDSpanTypes.TEST).start().finish();

    writer.waitForTraces(1);

    List trace = writer.firstTrace();
    assertEquals(1, trace.size());

    datadog.trace.core.DDSpan span = (datadog.trace.core.DDSpan) trace.get(0);
    assertEquals("test", span.getOperationName().toString());
  }

  @Test
  void testSessionTestModuleAndTestSuiteIDsAreNullified() throws Exception {
    tracer.addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE);

    datadog.trace.bootstrap.instrumentation.api.AgentSpan testSpan =
        tracer.buildSpan("test").withSpanType(DDSpanTypes.TEST).start();
    testSpan.setTag(Tags.TEST_SESSION_ID, "session ID");
    testSpan.setTag(Tags.TEST_MODULE_ID, "module ID");
    testSpan.setTag(Tags.TEST_SUITE_ID, "suite ID");
    testSpan.setTag("random tag", "random value");
    testSpan.finish();

    writer.waitForTraces(1);

    List trace = writer.firstTrace();
    assertEquals(1, trace.size());

    datadog.trace.core.DDSpan span = (datadog.trace.core.DDSpan) trace.get(0);

    assertNull(span.getTag(Tags.TEST_SESSION_ID));
    assertNull(span.getTag(Tags.TEST_MODULE_ID));
    assertNull(span.getTag(Tags.TEST_SUITE_ID));
    assertEquals("random value", span.getTag("random tag"));
  }
}
