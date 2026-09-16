package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.AppSecContext;
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
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code AppSecInterceptor.publish() -> AppSecContext.reportBlockFailure()} path, driven
 * through the public {@code onRequest()} entry point, which always ends in a {@link
 * BlockingException} when the callback returns a blocking flow.
 */
class AppSecInterceptorBlockFailureTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private Request request;
  private RequestContext ctx;
  private TraceSegment traceSegment;
  private AgentSpan span;

  @BeforeEach
  void setup() {
    request = new Request.Builder().url("http://example.com").build();
    traceSegment = mock(TraceSegment.class);
    ctx = mock(RequestContext.class);
    when(ctx.getTraceSegment()).thenReturn(traceSegment);

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
  void reportsBlockFailureWhenBlockingResponseCannotBeCommitted() {
    final BlockResponseFunction brf = blockResponseFunction(false);
    final AppSecContext appSecCtx = mock(AppSecContext.class);
    doReturn(appSecCtx).when(ctx).getData(RequestContextSlot.APPSEC);

    assertThrows(BlockingException.class, this::onRequest);

    verify(brf, times(1)).tryCommitBlockingResponse(traceSegment, RBA);
    verify(appSecCtx, times(1)).reportBlockFailure();
  }

  @Test
  void doesNotReportBlockFailureWhenBlockingResponseIsCommitted() {
    final BlockResponseFunction brf = blockResponseFunction(true);

    assertThrows(BlockingException.class, this::onRequest);

    verify(brf, times(1)).tryCommitBlockingResponse(traceSegment, RBA);
    verify(ctx, never()).getData(any(RequestContextSlot.class));
  }

  @Test
  void doesNotThrowWhenAppSecSlotDoesNotHoldAnAppSecContext() {
    final BlockResponseFunction brf = blockResponseFunction(false);

    // null in the AppSec slot
    doReturn(null).when(ctx).getData(RequestContextSlot.APPSEC);
    assertThrows(BlockingException.class, this::onRequest);

    // a foreign object in the AppSec slot
    doReturn("not an AppSecContext").when(ctx).getData(RequestContextSlot.APPSEC);
    assertThrows(BlockingException.class, this::onRequest);

    verify(brf, times(2)).tryCommitBlockingResponse(traceSegment, RBA);
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

  private BlockResponseFunction blockResponseFunction(final boolean committed) {
    final BlockResponseFunction brf = mock(BlockResponseFunction.class);
    when(brf.tryCommitBlockingResponse(traceSegment, RBA)).thenReturn(committed);
    when(ctx.getBlockResponseFunction()).thenReturn(brf);
    return brf;
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
