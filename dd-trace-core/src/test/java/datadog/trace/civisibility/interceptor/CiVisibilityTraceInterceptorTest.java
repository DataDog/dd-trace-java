package datadog.trace.civisibility.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.converter.DDSpanTypesConverter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class CiVisibilityTraceInterceptorTest extends DDCoreJavaSpecification {

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
  void discardATraceThatDoesNotComeFromCiApp() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);
    tracer.buildSpan("datadog", "sample-span").start().finish();

    assertEquals(0, writer.size());
  }

  @Test
  void doNotDiscardATraceThatComesFromCiApp() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);

    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "sample-span").start();
    span.spanContext().setOrigin(CIConstants.CIAPP_TEST_ORIGIN);
    span.finish();

    // expect:
    assertEquals(1, writer.size());
  }

  @TableTest({
    "scenario         | spanType                    ",
    "test             | DDSpanTypes.TEST            ",
    "test suite end   | DDSpanTypes.TEST_SUITE_END  ",
    "test module end  | DDSpanTypes.TEST_MODULE_END ",
    "test session end | DDSpanTypes.TEST_SESSION_END"
  })
  void addTracerVersionToSpansOfType(@ConvertWith(DDSpanTypesConverter.class) String spanType)
      throws InterruptedException, TimeoutException {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);

    DDSpan span =
        (DDSpan) tracer.buildSpan("datadog", "sample-span").withSpanType(spanType).start();
    span.spanContext().setOrigin(CIConstants.CIAPP_TEST_ORIGIN);
    span.finish();
    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan receivedSpan = trace.get(0);
    assertNotNull(receivedSpan.getTag(DDTags.LIBRARY_VERSION_TAG_KEY));
  }
}
