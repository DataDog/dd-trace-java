package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LambdaPayloadCaptureTest extends DDCoreJavaSpecification {
  @Test
  void disabledCaptureAddsNoPayloadTags() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.startSpan("test", "test");
      LambdaPayloadCapture.capture(Config.get(), span, "function.request", "{\"body\":\"hello\"}");
      assertNull(span.getTag("function.request.body"));
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = "capture.lambda.payload", value = "true")
  void nativeRequestAndResponseCapturePreservesApplicationStreams() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {

      byte[] request =
          "{\"items\":[true,null,42],\"body\":\"{\\\"password\\\":\\\"secret-value\\\",\\\"value\\\":5}\"}"
              .getBytes(StandardCharsets.UTF_8);
      ByteArrayInputStream input = new ByteArrayInputStream(request);
      LambdaInvocation invocation =
          LambdaInvocation.start(tracer, Config.get(), input, "request", "fn", "1", null);
      assertEquals(request.length, input.available());
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      response.write("{\"statusCode\":200,\"token\":123}".getBytes(StandardCharsets.UTF_8));
      invocation.onExit(response, null);
      invocation.finish();
      AgentSpan span = invocation.span();
      assertEquals("true", span.getTag("function.request.items.0"));
      assertEquals("null", span.getTag("function.request.items.1"));
      assertEquals("42", span.getTag("function.request.items.2"));
      assertEquals("redacted", span.getTag("function.request.body.password"));
      assertEquals("5", span.getTag("function.request.body.value"));
      assertEquals("200", span.getTag("function.response.statusCode"));
      assertEquals("redacted", span.getTag("function.response.token"));
      assertTrue(response.toString("UTF-8").contains("123"));
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = "capture.lambda.payload", value = "true")
  @WithConfig(key = "capture.lambda.payload.max.depth", value = "0")
  void depthLimitedJsonStillRedactsNestedAndEncodedSecrets() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.startSpan("test", "test");
      LambdaPayloadCapture.capture(
          Config.get(),
          span,
          "function.request",
          "{\"nested\":{\"Authorization\":\"hidden\",\"none\":null},\"body\":\"{\\\"api_key\\\":\\\"hidden\\\"}\"}");
      String tag = (String) span.getTag("function.request");
      assertFalse(tag.contains("hidden"));
      assertTrue(tag.contains("redacted"));
      assertTrue(tag.contains("null"));
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = "capture.lambda.payload", value = "true")
  void truncationCountsUnicodeCodePointsAndMalformedPayloadIsIgnored() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      AgentSpan span = tracer.startSpan("test", "test");
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 4100; i++) {
        text.append("\uD83D\uDE00");
      }
      LambdaPayloadCapture.capture(
          Config.get(),
          span,
          "function.request",
          java.util.Collections.singletonMap("text", text.toString()));
      String tag = (String) span.getTag("function.request.text");
      assertEquals(4096, tag.codePointCount(0, tag.length()));
      LambdaPayloadCapture.capture(Config.get(), span, "function.response", "malformed");
      assertNull(span.getTag("function.response"));
      span.finish();
    } finally {
      tracer.close();
    }
  }
}
