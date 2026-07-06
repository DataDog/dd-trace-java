package datadog.trace.civisibility.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class CiVisibilityApmProtocolInterceptorTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void testSuiteAndTestModuleSpansAreFilteredOut() throws InterruptedException, TimeoutException {
    tracer.addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE);

    tracer
        .buildSpan("datadog", "test-module")
        .withSpanType(DDSpanTypes.TEST_MODULE_END)
        .start()
        .finish();
    tracer
        .buildSpan("datadog", "test-suite")
        .withSpanType(DDSpanTypes.TEST_SUITE_END)
        .start()
        .finish();
    tracer.buildSpan("datadog", "test").withSpanType(DDSpanTypes.TEST).start().finish();

    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan span = trace.get(0);
    assertEquals("test", span.getOperationName().toString());
  }

  @Test
  void testSessionTestModuleAndTestSuiteIdsAreNullified()
      throws InterruptedException, TimeoutException {
    tracer.addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE);

    DDSpan testSpan =
        (DDSpan) tracer.buildSpan("datadog", "test").withSpanType(DDSpanTypes.TEST).start();
    testSpan.setTag(Tags.TEST_SESSION_ID, "session ID");
    testSpan.setTag(Tags.TEST_MODULE_ID, "module ID");
    testSpan.setTag(Tags.TEST_SUITE_ID, "suite ID");
    testSpan.setTag("random tag", "random value");
    testSpan.finish();

    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan span = trace.get(0);

    assertNull(span.getTag(Tags.TEST_SESSION_ID));
    assertNull(span.getTag(Tags.TEST_MODULE_ID));
    assertNull(span.getTag(Tags.TEST_SUITE_ID));
    assertEquals("random value", span.getTag("random tag"));
  }
}
