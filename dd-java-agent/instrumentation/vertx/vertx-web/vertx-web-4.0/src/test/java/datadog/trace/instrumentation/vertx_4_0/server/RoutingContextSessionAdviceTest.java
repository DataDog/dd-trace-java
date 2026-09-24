package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.ext.web.Session;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the blocking behavior of {@link RoutingContextSessionAdvice} by calling the advice method
 * directly: on a {@link Flow.Action.RequestBlockingAction} it commits the blocking response and
 * throws a {@link BlockingException}, but fails open (returns without throwing) when there is no
 * {@link BlockResponseFunction}.
 *
 * <p>vertx-web 3.4 has an identical copy of this advice, covered by its own test.
 */
class RoutingContextSessionAdviceTest {

  private static final String SESSION_ID = "session-id";
  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON);

  private AgentTracer.TracerAPI originalTracer;
  private TraceSegment traceSegment;
  private RequestContext reqCtx;
  private Session session;
  private Flow<Void> flow;
  private String receivedSessionId;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    originalTracer = AgentTracer.get();
    traceSegment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    // the build rewrites @ActiveRequestContext to read the context from the active span, and
    // skips the advice unless the context carries AppSec data
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(new Object());
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);
    session = mock(Session.class);
    when(session.id()).thenReturn(SESSION_ID);
    flow = mock(Flow.class);

    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    when(callbackProvider.getCallback(EVENTS.requestSession()))
        .thenReturn(
            (ctx, id) -> {
              receivedSessionId = id;
              return flow;
            });
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void commitsAndThrowsOnBlockingAction() {
    when(flow.getAction()).thenReturn(RBA);
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction();
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertThrows(BlockingException.class, () -> RoutingContextSessionAdvice.after(reqCtx, session));

    assertEquals(SESSION_ID, receivedSessionId);
    brf.assertCommittedOnce(traceSegment);
  }

  @Test
  void failsOpenWithoutBlockResponseFunction() {
    when(flow.getAction()).thenReturn(RBA);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertDoesNotThrow(() -> RoutingContextSessionAdvice.after(reqCtx, session));

    assertEquals(SESSION_ID, receivedSessionId);
  }

  @Test
  void doesNotCommitWithoutBlockingAction() {
    when(flow.getAction()).thenReturn(Flow.Action.Noop.INSTANCE);
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction();
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertDoesNotThrow(() -> RoutingContextSessionAdvice.after(reqCtx, session));

    assertEquals(SESSION_ID, receivedSessionId);
    assertEquals(0, brf.calls);
  }

  /**
   * Hand-written fake that only implements the abstract 5-arg method, so it records the commit
   * whichever {@code tryCommitBlockingResponse} overload the production code calls.
   */
  private static final class RecordingBlockResponseFunction implements BlockResponseFunction {
    private int calls;
    private TraceSegment lastSegment;
    private int lastStatusCode;
    private BlockingContentType lastTemplateType;

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      calls++;
      lastSegment = segment;
      lastStatusCode = statusCode;
      lastTemplateType = templateType;
      return true;
    }

    private void assertCommittedOnce(TraceSegment expectedSegment) {
      assertEquals(1, calls);
      assertSame(expectedSegment, lastSegment);
      assertEquals(RBA.getStatusCode(), lastStatusCode);
      assertEquals(RBA.getBlockingContentType(), lastTemplateType);
    }
  }
}
