package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@WithConfig(key = "universal.instrumentation", value = "false")
class LambdaNativeCompatibilityTest extends DDCoreJavaSpecification {
  private static final String HTTP =
      "{\"version\":\"2.0\",\"rawPath\":\"/\",\"rawQueryString\":\"\",\"headers\":{\"host\":\"api.example.com\"},\"requestContext\":{\"apiId\":\"api\",\"domainName\":\"api.example.com\",\"stage\":\"$default\",\"http\":{\"method\":\"GET\",\"path\":\"/\"}}}";

  @TableTest({
    "dropTrace | status",
    "false     | 200   ",
    "false     | 503   ",
    "true      | 503   "
  })
  void nativeCompletionUsesTheConfiguredWriter(boolean dropTrace, int status) {
    ListWriter writer = new ListWriter();
    Sampler sampler = mock(Sampler.class);
    when(sampler.sample(any())).thenReturn(!dropTrace);
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      AgentTracer.LambdaInvocation invocation =
          tracer.startLambdaInvocation(
              HTTP, "request", "function", "1", "arn:aws:lambda:us-east-1:123:function:function");
      DDSpan span = (DDSpan) invocation.span();
      assertEquals("aws.httpapi", span.getLocalRootSpan().getOperationName().toString());
      assertTrue(span.getParentId() != 0);
      assertEquals("request", span.getTag("request_id"));
      try (ContextScope scope = invocation.activate()) {
        invocation.onExit(singletonMap("statusCode", status), null);
      }
      invocation.finish();
      invocation.finish();
      assertEquals(status >= 500, span.isError());
      assertEquals(status >= 500, span.getLocalRootSpan().isError());
      assertNull(tracer.activeSpan());
      assertEquals(dropTrace ? 0 : 1, writer.size());
    } finally {
      tracer.close();
    }
  }

  @Test
  void concurrentInvocationsKeepRequestIdentityAndScopesSeparate() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CyclicBarrier activated = new CyclicBarrier(2);
    try {
      List<Future<DDSpan>> invocations = new ArrayList<>();
      for (String requestId : new String[] {"first", "second"}) {
        invocations.add(
            workers.submit(
                () -> {
                  AgentTracer.LambdaInvocation invocation =
                      tracer.startLambdaInvocation("{}", requestId, "function", "1", null);
                  try (ContextScope scope = invocation.activate()) {
                    activated.await(5, TimeUnit.SECONDS);
                    assertSame(invocation.span(), tracer.activeSpan());
                    assertEquals(requestId, tracer.activeSpan().getTag("request_id"));
                    DDSpan child = (DDSpan) tracer.startSpan("test", "child");
                    assertEquals(invocation.span().getSpanId(), child.getParentId());
                    child.finish();
                    invocation.onExit(null, null);
                  } finally {
                    invocation.finish();
                  }
                  assertNull(tracer.activeSpan());
                  return (DDSpan) invocation.span();
                }));
      }
      DDSpan first = invocations.get(0).get(10, TimeUnit.SECONDS);
      DDSpan second = invocations.get(1).get(10, TimeUnit.SECONDS);
      assertNotEquals(first.getSpanId(), second.getSpanId());
      assertNotEquals(first.getTraceId(), second.getTraceId());
      assertEquals(2, writer.size());
    } finally {
      workers.shutdownNow();
      tracer.close();
    }
  }

  @TableTest({
    "responseFailure",
    "false          ",
    "true           "
  })
  void nativeAppSecCallbacksAndCleanupSurviveResponseFailures(boolean responseFailure)
      throws Exception {
    boolean previousAppSec = ActiveSubsystems.APPSEC_ACTIVE;
    AgentTracer.TracerAPI previousTracer = AgentTracer.get();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AtomicInteger starts = new AtomicInteger();
    AtomicInteger ends = new AtomicInteger();
    Object appSecContext = new Object();
    try {
      AgentTracer.forceRegister(tracer);
      ActiveSubsystems.APPSEC_ACTIVE = true;
      tracer
          .getSubscriptionService(RequestContextSlot.APPSEC)
          .registerCallback(
              EVENTS.requestStarted(),
              () -> {
                starts.incrementAndGet();
                return new Flow.ResultFlow<>(appSecContext);
              });
      tracer
          .getSubscriptionService(RequestContextSlot.APPSEC)
          .registerCallback(
              EVENTS.requestEnded(),
              (ctx, info) -> {
                assertSame(appSecContext, ctx.getData(RequestContextSlot.APPSEC));
                ends.incrementAndGet();
                ((AgentSpan) info).setMetaStruct("appsec", singletonMap("event", "test"));
                return Flow.ResultFlow.empty();
              });

      byte[] bytes = HTTP.getBytes(StandardCharsets.UTF_8);
      ByteArrayInputStream input = new ByteArrayInputStream(bytes);
      LambdaInvocation invocation =
          LambdaInvocation.start(tracer, Config.get(), input, "request", "function", "1", null);
      assertEquals(bytes.length, input.available());
      assertEquals(1, starts.get());
      ByteArrayOutputStream output =
          responseFailure
              ? new ByteArrayOutputStream() {
                @Override
                public byte[] toByteArray() {
                  throw new AssertionError("bad response");
                }
              }
              : new ByteArrayOutputStream();
      output.write("{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
      try (ContextScope scope = invocation.activate()) {
        if (responseFailure) {
          assertThrows(AssertionError.class, () -> invocation.onExit(output, null));
        } else {
          invocation.onExit(output, null);
        }
      } finally {
        invocation.finish();
      }
      assertEquals(1, ends.get());
      assertNull(tracer.activeSpan());
      DDSpan root = (DDSpan) invocation.span().getLocalRootSpan();
      assertEquals(singletonMap("event", "test"), root.getMetaStruct().get("appsec"));
    } finally {
      AgentTracer.forceRegister(previousTracer);
      ActiveSubsystems.APPSEC_ACTIVE = previousAppSec;
      tracer.close();
    }
  }
}
