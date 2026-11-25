package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LambdaNativeResponseTest extends DDCoreJavaSpecification {
  @ParameterizedTest
  @ValueSource(ints = {200, 404, 499, 500, 503, 599, 600})
  void genericResponseStatusSurvivesWithoutAnHttpTrigger(int status) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {

      LambdaInvocation invocation =
          LambdaInvocation.start(
              tracer, Config.get(), "{\"custom\":true}", "request", "function", "1", null);
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      byte[] bytes = ("{\"statusCode\":" + status + "}").getBytes(StandardCharsets.UTF_8);
      response.write(bytes, 0, bytes.length);
      invocation.onExit(response, null);
      invocation.finish();
      assertEquals(Integer.toString(status), invocation.span().getTag(Tags.HTTP_STATUS).toString());
      assertEquals(status >= 500 && status <= 599, invocation.span().isError());
      assertEquals(new String(bytes, StandardCharsets.UTF_8), response.toString());

    } finally {
      tracer.close();
    }
  }

  @Test
  void thrownErrorTakesPrecedenceOverPartiallyWrittenResponse() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {

      LambdaInvocation invocation =
          LambdaInvocation.start(tracer, Config.get(), "{}", "request", "function", "1", null);
      invocation.onExit("{\"statusCode\":200}", new IllegalStateException("failed"));
      invocation.finish();
      assertTrue(invocation.span().isError());
      assertNull(invocation.span().getTag(Tags.HTTP_STATUS));
      assertEquals("failed", invocation.span().getTag("error.message"));
    } finally {
      tracer.close();
    }
  }

  @Test
  void eventSerializationPreservesTheRemainingInputBytes() {
    ByteArrayInputStream input =
        new ByteArrayInputStream("x{\"body\":1}".getBytes(StandardCharsets.UTF_8));
    assertEquals('x', input.read());
    assertEquals(1.0, LambdaEventData.payload(input).get("body"));
    assertEquals('{', input.read());
    assertTrue(LambdaEventData.payload("invalid json").isEmpty());
    assertFalse(LambdaEventData.payload("{}").containsKey("statusCode"));
  }
}
