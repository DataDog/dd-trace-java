package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

@WithConfig(key = "service", value = "lambda-service")
class LambdaNativeInvocationTest extends DDCoreJavaSpecification {
  @Test
  void realInvocationParentsChildrenAndFinishesOnce() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      LambdaNativeClient client = mock(LambdaNativeClient.class);
      LambdaNativeClient.Invocation exchange = mock(LambdaNativeClient.Invocation.class);
      when(client.begin(any(), anyLong(), anyString())).thenReturn(exchange);
      AgentTracer.LambdaInvocation invocation =
          LambdaInvocation.start(
              tracer,
              Config.get(),
              client,
              "{}",
              "request-1",
              "MyFunction",
              "42",
              "arn:aws:lambda:us-east-1:123:function:MyFunction");
      DDSpan span = (DDSpan) invocation.span();
      assertEquals("aws.lambda", span.getOperationName().toString());
      assertEquals("MyFunction", span.getResourceName().toString());
      assertEquals("lambda-service", span.getServiceName());
      assertEquals(DDSpanTypes.SERVERLESS, span.getSpanType());
      assertEquals("request-1", span.getTag("request_id"));
      assertNull(span.getTag("cold_start"));
      Context before = Context.current();
      try (ContextScope scope = invocation.activate()) {
        assertSame(span, AgentSpan.fromContext(Context.current()));
        DDSpan child = (DDSpan) tracer.startSpan("test", "work");
        assertEquals(span.getSpanId(), child.getParentId());
        child.finish();
        invocation.onExit(null, null);
      }
      invocation.finish();
      invocation.finish();
      assertSame(before, Context.current());
      assertEquals(1, writer.size());
      assertEquals(2, writer.get(0).size());
      verify(exchange, times(1)).complete(span);
    } finally {
      tracer.close();
    }
  }

  @Test
  void errorKeepsTheFunctionResourceAndScopeCleanup() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      LambdaNativeClient client = mock(LambdaNativeClient.class);
      when(client.begin(any(), anyLong(), anyString()))
          .thenReturn(mock(LambdaNativeClient.Invocation.class));
      AgentTracer.LambdaInvocation invocation =
          LambdaInvocation.start(
              tracer, Config.get(), client, "{}", "request-1", "function", "1", null);
      try (ContextScope scope = invocation.activate()) {
        invocation.span().setResourceName("GET /route");
        invocation.onExit(null, new IllegalStateException("handler failed"));
      } finally {
        invocation.finish();
      }
      assertTrue(invocation.span().isError());
      assertEquals("function", invocation.span().getResourceName().toString());
      assertNull(tracer.activeSpan());
      assertEquals(1, writer.size());
    } finally {
      tracer.close();
    }
  }

  @Test
  void legacyGateDoesNotCreateNativeInvocations() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertTrue(tracer.isUniversalInstrumentationEnabled());
      assertNull(tracer.startLambdaInvocation("{}", "request-1", "function", "1", null));
    } finally {
      tracer.close();
    }
  }
}
