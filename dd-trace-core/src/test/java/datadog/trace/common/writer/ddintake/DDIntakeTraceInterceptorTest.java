package datadog.trace.common.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(100)
class DDIntakeTraceInterceptorTest extends DDCoreJavaSpecification {

  ListWriter writer;
  CoreTracer tracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
    tracer.addTraceInterceptor(DDIntakeTraceInterceptor.INSTANCE);
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @TableTest({
    "scenario     | httpStatus | expectedHttpStatus",
    "null         |            |                   ",
    "empty string | ''         |                   ",
    "string 500   | '500'      | 500               ",
    "integer 500  | 500        | 500               ",
    "integer 600  | 600        |                   "
  })
  void testNormalizationForDdIntake(Object httpStatus, Integer expectedHttpStatus)
      throws InterruptedException, TimeoutException {
    tracer
        .buildSpan("datadog", "my-operation-name")
        .withResourceName("my-resource-name")
        .withSpanType("my-span-type")
        .withServiceName("my-service-name")
        .withTag("some-tag-key", "some-tag-value")
        .withTag("env", "     My_____Env     ")
        .withTag(Tags.HTTP_STATUS, httpStatus)
        .start()
        .finish();
    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("my-service-name", span.getServiceName());
    assertEquals("my_operation_name", span.getOperationName().toString());
    assertEquals("my-resource-name", span.getResourceName().toString());
    assertEquals("my-span-type", span.getSpanType());
    assertEquals("some-tag-value", span.getTag("some-tag-key"));
    assertEquals("my_env", span.getTag("env"));
    assertEquals(expectedHttpStatus, span.getTag(Tags.HTTP_STATUS));
  }

  @Test
  void testNormalizationDoesNotImplicitlyConvertSpanType()
      throws InterruptedException, TimeoutException {
    UTF8BytesString originalSpanType = UTF8BytesString.create("a UTF8 span type");
    tracer
        .buildSpan("datadog", "my-operation-name")
        .withSpanType(originalSpanType)
        .start()
        .finish();

    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals(originalSpanType, span.getType());
  }

  @Test
  void testDefaultEnvSetting() throws InterruptedException, TimeoutException {
    tracer.buildSpan("datadog", "my-operation-name").start().finish();
    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("none", span.getTag("env"));
  }
}
