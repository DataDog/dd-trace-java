package datadog.trace.lambda;

import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class LambdaNativePropagationTest extends DDCoreJavaSpecification {
  private static final String PARENT =
      "{\"x-datadog-trace-id\":\"18446744073709551615\",\"x-datadog-parent-id\":\"42\",\"x-datadog-tags\":\"_dd.p.tid=0123456789abcdef\",\"x-datadog-sampling-priority\":\"2\"}";

  @TableTest({
    "location        | parent",
    "request         | 42    ",
    "headers         | 42    ",
    "invalid-request | 42    "
  })
  void genericCarriersContinueUnsignedTraceAndReportTheSameParent(String location, long parent) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      String event =
          location.equals("request")
              ? "{\"request\":{\"headers\":" + PARENT + "},\"headers\":{}}"
              : "{\"request\":{\"headers\":{\"traceparent\":\"invalid\"}},\"headers\":"
                  + PARENT
                  + "}";
      LambdaNativeClient client = client();
      LambdaInvocation invocation = start(tracer, client, event);
      DDSpan span = (DDSpan) invocation.span();
      assertEquals("0123456789abcdefffffffffffffffff", span.getTraceId().toHexString());
      assertEquals(parent, span.getParentId());
      assertEquals(2, span.getSamplingPriority());
      verify(client).begin(eq(span), eq(parent), eq("request"));
      invocation.finish();
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "behavior",
    "ignore  ",
    "restart "
  })
  void extractionBehaviorAppliesToCarrierAndGeneratedContexts(String behavior) {
    injectSysConfig("trace.propagation.behavior.extract", behavior);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      for (String event :
          new String[] {
            "{\"headers\":" + PARENT + "}",
            "{\"Execution\":{\"Id\":\"execution\"},\"State\":{\"Name\":\"step\",\"EnteredTime\":\"time\"}}"
          }) {
        LambdaInvocation invocation = start(tracer, client(), event);
        DDSpan span = (DDSpan) invocation.span();
        assertEquals(0, span.getParentId());
        assertNotEquals("0123456789abcdefffffffffffffffff", span.getTraceId().toHexString());
        assertEquals(behavior.equals("restart") ? 1 : 0, span.getLinks().size());
        invocation.finish();
      }
    } finally {
      tracer.close();
    }
  }

  @Test
  @WithConfig(key = "trace.propagation.style.extract", value = "tracecontext,baggage")
  void w3cBaggageIsActiveOnlyDuringTheInvocation() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      LambdaInvocation invocation =
          start(
              tracer,
              client(),
              "{\"headers\":{\"traceparent\":\"00-0123456789abcdeffedcba98765432100-0000000000000042-01\",\"baggage\":\"tenant=acme\"}}");
      try (ContextScope scope = invocation.activate()) {
        assertEquals("acme", Baggage.fromContext(Context.current()).asMap().get("tenant"));
      }
      invocation.finish();
      assertNull(Baggage.fromContext(Context.current()));
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "style  ",
    "none   ",
    "baggage"
  })
  void disabledExtractionDoesNotGenerateAStepFunctionsParent(String style) {
    injectSysConfig("trace.propagation.style.extract", style);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      LambdaInvocation invocation =
          start(
              tracer,
              client(),
              "{\"Execution\":{\"Id\":\"execution\"},\"State\":{\"Name\":\"step\",\"EnteredTime\":\"time\"}}");
      assertEquals(0, ((DDSpan) invocation.span()).getParentId());
      invocation.finish();
    } finally {
      tracer.close();
    }
  }

  private LambdaNativeClient client() {
    LambdaNativeClient client = mock(LambdaNativeClient.class);
    when(client.begin(any(), anyLong(), anyString()))
        .thenReturn(mock(LambdaNativeClient.Invocation.class));
    return client;
  }

  private LambdaInvocation start(CoreTracer tracer, LambdaNativeClient client, Object event) {
    return LambdaInvocation.start(
        tracer, Config.get(), client, event, "request", "function", "1", null);
  }
}
