package datadog.trace.instrumentation.okhttp2;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squareup.okhttp.Request;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the wiring between {@code AppSecInterceptor.publish()} and {@link
 * BlockResponseFunction#tryCommitBlockingResponse(RequestContext,
 * Flow.Action.RequestBlockingAction)}, which is the method that reports block failures. Driven
 * through the public {@code onRequest()} entry point, which always ends in a {@link
 * BlockingException} when the callback returns a blocking flow.
 */
class AppSecInterceptorBlockFailureTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private Request request;
  private RequestContext ctx;
  private AgentSpan span;

  @BeforeEach
  void setup() {
    request = new Request.Builder().url("http://example.com").build();
    ctx = mock(RequestContext.class);
    when(ctx.getTraceSegment()).thenReturn(mock(TraceSegment.class));

    span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(ctx);
    when(span.getSpanId()).thenReturn(1L);

    final BiFunction<RequestContext, HttpClientRequest, Flow<Void>> callback =
        (requestContext, clientRequest) -> blockingFlow();
    final CallbackProvider cbp = mock(CallbackProvider.class);
    when(cbp.getCallback(EVENTS.httpClientRequest())).thenReturn(callback);

    final AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void commitsBlockingResponseWithTheRequestContext() {
    final BlockResponseFunction brf = mock(BlockResponseFunction.class);
    when(ctx.getBlockResponseFunction()).thenReturn(brf);

    assertThrows(BlockingException.class, this::onRequest);

    verify(brf, times(1)).tryCommitBlockingResponse(ctx, RBA);
  }

  @Test
  void doesNotTouchAppSecSlotWhenThereIsNoBlockResponseFunction() {
    when(ctx.getBlockResponseFunction()).thenReturn(null);

    assertThrows(BlockingException.class, this::onRequest);

    verify(ctx, never()).getData(any(RequestContextSlot.class));
  }

  private void onRequest() {
    AppSecInterceptor.onRequest(span, false, "http://example.com", request);
  }

  private static Flow<Void> blockingFlow() {
    return new Flow<Void>() {
      @Override
      public Action getAction() {
        return RBA;
      }

      @Override
      public Void getResult() {
        return null;
      }
    };
  }
}
