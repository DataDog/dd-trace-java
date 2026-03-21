package datadog.trace.common.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

@Timeout(100)
class DDIntakeTraceInterceptorTest extends DDCoreSpecification {

  private ListWriter writer;
  private datadog.trace.core.CoreTracer tracer;

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
    "null status  | null       | null              ",
    "empty status | empty      | null              ",
    "500 string   | 500str     | 500               ",
    "500 int      | 500int     | 500               ",
    "600 int      | 600int     | null              "
  })
  @ParameterizedTest(name = "[{index}] test normalization for dd intake - {0}")
  void testNormalizationForDdIntake(String httpStatus, String expectedHttpStatus) throws Exception {
    Integer expectedHttpStatusInt =
        "null".equals(expectedHttpStatus) || expectedHttpStatus == null
            ? null
            : Integer.parseInt(expectedHttpStatus);
    Object parsedHttpStatus = parseHttpStatus(httpStatus);

    datadog.trace.bootstrap.instrumentation.api.AgentSpan spanBuilder =
        tracer
            .buildSpan("my-operation-name")
            .withResourceName("my-resource-name")
            .withSpanType("my-span-type")
            .withServiceName("my-service-name")
            .withTag("some-tag-key", "some-tag-value")
            .withTag("env", "     My_____Env     ")
            .start();

    if (parsedHttpStatus != null) {
      if (parsedHttpStatus instanceof String) {
        ((datadog.trace.core.DDSpan) spanBuilder)
            .setTag(Tags.HTTP_STATUS, (String) parsedHttpStatus);
      } else if (parsedHttpStatus instanceof Integer) {
        ((datadog.trace.core.DDSpan) spanBuilder)
            .setTag(Tags.HTTP_STATUS, (Integer) parsedHttpStatus);
      }
    }
    spanBuilder.finish();
    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan span = trace.get(0);

    assertEquals("my-service-name", span.getServiceName());
    assertEquals("my_operation_name", span.getOperationName().toString());
    assertEquals("my-resource-name", span.getResourceName().toString());
    assertEquals("my-span-type", span.getSpanType().toString());
    assertEquals("some-tag-value", span.getTag("some-tag-key"));
    assertEquals("my_env", span.getTag("env"));
    assertEquals(expectedHttpStatusInt, span.getTag(Tags.HTTP_STATUS));
  }

  @Test
  void testNormalizationDoesNotImplicitlyConvertSpanType() throws Exception {
    UTF8BytesString originalSpanType = UTF8BytesString.create("a UTF8 span type");
    tracer.buildSpan("my-operation-name").withSpanType(originalSpanType).start().finish();

    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan span = trace.get(0);
    assertEquals(originalSpanType.toString(), span.getSpanType().toString());
  }

  @Test
  void testDefaultEnvSetting() throws Exception {
    tracer.buildSpan("my-operation-name").start().finish();
    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan span = trace.get(0);
    assertEquals("none", span.getTag("env"));
  }

  private static Object parseHttpStatus(String str) {
    if (str == null) return null;
    switch (str.trim()) {
      case "null":
        return null;
      case "empty":
        return "";
      case "500str":
        return "500";
      case "500int":
        return 500;
      case "600int":
        return 600;
      default:
        throw new IllegalArgumentException("Unknown httpStatus: " + str);
    }
  }
}
