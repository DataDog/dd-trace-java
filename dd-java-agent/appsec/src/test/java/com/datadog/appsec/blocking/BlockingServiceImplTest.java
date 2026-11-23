package com.datadog.appsec.blocking;

import static com.datadog.appsec.event.data.KnownAddresses.USER_ID;
import static datadog.appsec.api.blocking.BlockingContentType.HTML;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import datadog.appsec.api.blocking.BlockingDetails;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockingServiceImplTest extends DDJavaSpecification {

  private final EventDispatcher eps = new EventDispatcher();
  private final BlockingServiceImpl bs = new BlockingServiceImpl(eps);
  private TracerAPI origTracer;

  @BeforeEach
  void setup() {
    origTracer = AgentTracer.get();
  }

  @AfterEach
  void cleanup() {
    AgentTracer.forceRegister(origTracer);
  }

  @Test
  void shouldBlockUser() {
    // setup
    EventDispatcher.DataSubscriptionSet set = new EventDispatcher.DataSubscriptionSet();
    set.addSubscription(singletonList((Address<?>) USER_ID), new BlockingUserListener());
    eps.subscribeDataAvailable(set);

    AppSecRequestContext appSecRequestContext = mock(AppSecRequestContext.class);
    RequestContext requestContext = mock(RequestContext.class);
    doReturn(appSecRequestContext).when(requestContext).getData(RequestContextSlot.APPSEC);
    registerTracerWithActiveRequestContext(requestContext);

    // when
    BlockingDetails result = bs.shouldBlockUser("blocked.user");

    // then
    assertNotNull(result);
    assertEquals(HTML, result.blockingContentType);
    assertEquals(405, result.statusCode);

    // when
    result = bs.shouldBlockUser("not.blocked.user");

    // then
    assertNull(result);
  }

  @Test
  void shouldBlockUserWithNoActiveSpan() {
    // setup
    TracerAPI tracerApi = mock(TracerAPI.class);
    AgentTracer.forceRegister(tracerApi);
    when(tracerApi.activeSpan()).thenReturn(null);

    // when
    BlockingDetails result = bs.shouldBlockUser("foo.bar");

    // then
    assertNull(result);
    verify(tracerApi).activeSpan();
  }

  @Test
  void tryCommitBlockingResponse() {
    // setup
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    TraceSegment mts = mock(TraceSegment.class);
    RequestContext requestContext = mock(RequestContext.class);
    when(requestContext.getBlockResponseFunction()).thenReturn(brf);
    when(requestContext.getTraceSegment()).thenReturn(mts);
    registerTracerWithActiveRequestContext(requestContext);
    when(brf.tryCommitBlockingResponse(mts, 405, HTML, emptyMap(), null)).thenReturn(true);

    // when
    boolean res = bs.tryCommitBlockingResponse(405, HTML, emptyMap());

    // then
    assertTrue(res);
    verify(brf).tryCommitBlockingResponse(mts, 405, HTML, emptyMap(), null);
    verify(mts).effectivelyBlocked();
  }

  @Test
  void tryCommitBlockingResponseWithoutActiveSpan() {
    // setup
    RequestContext reqCtx = mock(RequestContext.class);
    registerTracerWithActiveRequestContext(reqCtx);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    // when
    boolean res = assertDoesNotThrow(() -> bs.tryCommitBlockingResponse(405, HTML, emptyMap()));

    // then
    assertFalse(res);
    verify(reqCtx).getBlockResponseFunction();
  }

  @Test
  void tryCommitBlockingResponseWithoutBlockingResponseFunction() {
    // setup
    TracerAPI tracerApi = mock(TracerAPI.class);
    AgentTracer.forceRegister(tracerApi);
    when(tracerApi.activeSpan()).thenReturn(null);

    // when
    boolean res = assertDoesNotThrow(() -> bs.tryCommitBlockingResponse(405, HTML, emptyMap()));

    // then
    assertFalse(res);
    verify(tracerApi).activeSpan();
  }

  /** Registers a tracer whose active span exposes the given request context. */
  private static void registerTracerWithActiveRequestContext(RequestContext requestContext) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(requestContext);
    TracerAPI tracer = mock(TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    AgentTracer.forceRegister(tracer);
  }

  /** Blocks requests published with the {@code blocked.user} user id. */
  private static class BlockingUserListener implements DataListener {
    @Override
    public Priority getPriority() {
      return Priority.DEFAULT;
    }

    @Override
    public void onDataAvailable(
        ChangeableFlow flow,
        AppSecRequestContext reqCtx,
        DataBundle dataBundle,
        GatewayContext gwCtx) {
      if ("blocked.user".equals(dataBundle.get(USER_ID))) {
        flow.setAction(new Flow.Action.RequestBlockingAction(405, HTML));
      }
    }
  }
}
