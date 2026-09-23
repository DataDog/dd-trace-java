package datadog.trace.instrumentation.play.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PathExtractionHelpersTest {

  private static final String ORIGIN = "test.origin";

  private TracerAPI originalTracer;
  private CallbackProvider callbackProvider;
  private BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    originalTracer = AgentTracer.get();

    callbackProvider = mock(CallbackProvider.class);
    callback = mock(BiFunction.class);
    when(callbackProvider.getCallback(EVENTS.requestPathParams())).thenReturn(callback);

    TracerAPI tracer = mock(TracerAPI.class);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void nullParams_returnsNullWithoutCallingCallback() {
    RequestContext reqCtx = mock(RequestContext.class);

    assertNull(PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, null, ORIGIN));

    verify(callback, never()).apply(any(), any());
  }

  @Test
  void emptyParams_returnsNullWithoutCallingCallback() {
    RequestContext reqCtx = mock(RequestContext.class);

    assertNull(
        PathExtractionHelpers.callRequestPathParamsCallback(
            reqCtx, Collections.emptyMap(), ORIGIN));

    verify(callback, never()).apply(any(), any());
  }

  @Test
  void noCallbackRegistered_returnsNull() {
    when(callbackProvider.getCallback(EVENTS.requestPathParams())).thenReturn(null);
    RequestContext reqCtx = mock(RequestContext.class);

    assertNull(PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params(), ORIGIN));

    verify(reqCtx, never()).getBlockResponseFunction();
  }

  @Test
  void nonBlockingAction_returnsNull() {
    RequestContext reqCtx = mock(RequestContext.class);
    stubCallbackAction(Flow.Action.Noop.INSTANCE);

    assertNull(PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params(), ORIGIN));

    verify(reqCtx, never()).getBlockResponseFunction();
  }

  /**
   * Pins the intentional fail-open contract: without a {@link BlockResponseFunction} nothing can
   * commit a blocking response, so a refactor flipping this to fail-closed must fail here.
   */
  @Test
  void blockingActionWithoutBlockResponseFunction_failsOpen() {
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);
    stubCallbackAction(blockingAction());

    assertNull(PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params(), ORIGIN));

    verify(reqCtx).getBlockResponseFunction();
  }

  @Test
  void blockingActionWithBlockResponseFunction_commitsAndThrows() {
    RequestContext reqCtx = mock(RequestContext.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    Flow.Action.RequestBlockingAction rba = blockingAction();
    stubCallbackAction(rba);

    BlockingException exception =
        PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params(), ORIGIN);

    assertNotNull(exception);
    verify(brf).tryCommitBlockingResponse(reqCtx, rba);
  }

  @Test
  void blockingActionWithFailedCommit_stillThrows() {
    RequestContext reqCtx = mock(RequestContext.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    Flow.Action.RequestBlockingAction rba = blockingAction();
    when(brf.tryCommitBlockingResponse(reqCtx, rba)).thenReturn(false);
    stubCallbackAction(rba);

    assertNotNull(PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params(), ORIGIN));
  }

  @Test
  void callbackThrows_exceptionIsSwallowed() {
    RequestContext reqCtx = mock(RequestContext.class);
    when(callback.apply(any(), any())).thenThrow(new RuntimeException("boom"));

    assertNull(PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params(), ORIGIN));
  }

  private void stubCallbackAction(Flow.Action action) {
    @SuppressWarnings("unchecked")
    Flow<Void> flow = mock(Flow.class);
    when(flow.getAction()).thenReturn(action);
    when(callback.apply(any(), any())).thenReturn(flow);
  }

  private static Flow.Action.RequestBlockingAction blockingAction() {
    return new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);
  }

  private static Map<String, Object> params() {
    return Collections.singletonMap("id", "1");
  }
}
