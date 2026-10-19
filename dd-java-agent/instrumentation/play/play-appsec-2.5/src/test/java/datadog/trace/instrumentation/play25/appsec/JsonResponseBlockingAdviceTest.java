package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.api.libs.json.JsValue;
import play.api.libs.json.Json$;
import play.mvc.StatusHeader;

/**
 * Covers {@link ResultsStatusApplyAdvice} and {@link StatusHeaderSendJsonAdvice}: when the AppSec
 * response body callback returns a {@link Flow.Action.RequestBlockingAction} and a {@link
 * BlockResponseFunction} is present, the advice attempts to commit the blocking response and throws
 * {@link BlockingException} regardless of the commit result. These are response-body hooks: the WAF
 * already flagged content about to be sent to the client, so a failed commit must still abort the
 * request rather than let the flagged body through.
 */
class JsonResponseBlockingAdviceTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private RequestContext reqCtx;
  private TraceSegment segment;
  private AppSecContext appSecCtx;
  private Object receivedBody;

  @BeforeEach
  void setup() {
    segment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(segment);
    // these tests call the advice methods directly, so @RequiresRequestContext's bytecode
    // rewriting never runs; the getData(APPSEC) stub only matters for the default
    // tryCommitBlockingResponse(RequestContext, ...) method's reportBlockFailure() branch below. A
    // real AppSecContext (rather than a plain Object) is what makes that branch exercisable.
    appSecCtx = mock(AppSecContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);

    BiFunction<RequestContext, Object, Flow<Void>> callback =
        (ctx, body) -> {
          receivedBody = body;
          return blockingFlow();
        };
    CallbackProvider cbp = mock(CallbackProvider.class);
    when(cbp.getCallback(EVENTS.responseBody())).thenReturn(callback);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);
    when(tracer.activeSpan()).thenReturn(span);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void resultsStatusApplyThrowsWhenCommitSucceeds() {
    RecordingBlockResponseFunction brf = givenBlockResponseFunction(true);
    JsValue content = Json$.MODULE$.parse("{\"key\":\"value\"}");

    BlockingException ex =
        assertThrows(
            BlockingException.class, () -> ResultsStatusApplyAdvice.before(content, reqCtx));

    assertEquals("Blocked request (for Results$Status/apply)", ex.getMessage());
    // guards the handleResponseBody(RequestContext, JsValue, String) overload: the WAF callback
    // must receive the converted plain-Java value, not the raw JsValue
    assertEquals(Collections.singletonMap("key", "value"), receivedBody);
    brf.assertCommittedOnce(segment);
    verify(appSecCtx, never()).reportBlockFailure();
  }

  @Test
  void resultsStatusApplyDoesNotThrowWithoutBlockResponseFunction() {
    JsValue content = Json$.MODULE$.parse("{\"key\":\"value\"}");

    assertDoesNotThrow(() -> ResultsStatusApplyAdvice.before(content, reqCtx));

    verify(appSecCtx, never()).reportBlockFailure();
  }

  @Test
  void resultsStatusApplyStillThrowsWhenCommitFails() {
    RecordingBlockResponseFunction brf = givenBlockResponseFunction(false);
    JsValue content = Json$.MODULE$.parse("{\"key\":\"value\"}");

    BlockingException ex =
        assertThrows(
            BlockingException.class, () -> ResultsStatusApplyAdvice.before(content, reqCtx));

    assertEquals("Blocked request (for Results$Status/apply)", ex.getMessage());
    brf.assertCommittedOnce(segment);
    verify(appSecCtx).reportBlockFailure();
  }

  @Test
  void statusHeaderSendJsonThrowsWhenCommitSucceeds() {
    RecordingBlockResponseFunction brf = givenBlockResponseFunction(true);
    ObjectNode json = JsonNodeFactory.instance.objectNode().put("key", "value");

    BlockingException ex;
    try {
      ex =
          assertThrows(
              BlockingException.class, () -> StatusHeaderSendJsonAdvice.before(json, reqCtx));
    } finally {
      // reset the call depth incremented by before()
      CallDepthThreadLocalMap.decrementCallDepth(StatusHeader.class);
    }

    assertEquals("Blocked request (for StatusHeader/sendJson)", ex.getMessage());
    // the Object overload passes the body through with no conversion
    assertSame(json, receivedBody);
    brf.assertCommittedOnce(segment);
    verify(appSecCtx, never()).reportBlockFailure();
  }

  @Test
  void statusHeaderSendJsonDoesNotThrowWithoutBlockResponseFunction() {
    ObjectNode json = JsonNodeFactory.instance.objectNode().put("key", "value");

    try {
      assertDoesNotThrow(() -> StatusHeaderSendJsonAdvice.before(json, reqCtx));
    } finally {
      // reset the call depth incremented by before()
      CallDepthThreadLocalMap.decrementCallDepth(StatusHeader.class);
    }

    verify(appSecCtx, never()).reportBlockFailure();
  }

  @Test
  void statusHeaderSendJsonStillThrowsWhenCommitFails() {
    RecordingBlockResponseFunction brf = givenBlockResponseFunction(false);
    ObjectNode json = JsonNodeFactory.instance.objectNode().put("key", "value");

    BlockingException ex;
    try {
      ex =
          assertThrows(
              BlockingException.class, () -> StatusHeaderSendJsonAdvice.before(json, reqCtx));
    } finally {
      // reset the call depth incremented by before()
      CallDepthThreadLocalMap.decrementCallDepth(StatusHeader.class);
    }

    assertEquals("Blocked request (for StatusHeader/sendJson)", ex.getMessage());
    brf.assertCommittedOnce(segment);
    verify(appSecCtx).reportBlockFailure();
  }

  /**
   * Guards {@code handleResponseBody(RequestContext, JsValue, String)}'s early return when no
   * {@link CallbackProvider} is registered: the body must not be published to a nonexistent
   * callback, and no exception should propagate.
   */
  @Test
  void handleResponseBodyJsValueDoesNothingWithoutCallbackProvider() {
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(null);
    AgentTracer.forceRegister(tracer);
    JsValue content = Json$.MODULE$.parse("{\"key\":\"value\"}");

    assertDoesNotThrow(() -> BodyParserHelpers.handleResponseBody(reqCtx, content, "test"));
  }

  /**
   * Guards {@code handleResponseBody(RequestContext, JsValue, String)}'s early return when no
   * {@code responseBody()} callback is registered on the {@link CallbackProvider}: distinct from
   * the no-provider case above.
   */
  @Test
  void handleResponseBodyJsValueDoesNothingWithoutResponseBodyCallback() {
    CallbackProvider cbp = mock(CallbackProvider.class);
    when(cbp.getCallback(EVENTS.responseBody())).thenReturn(null);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);
    AgentTracer.forceRegister(tracer);
    JsValue content = Json$.MODULE$.parse("{\"key\":\"value\"}");

    assertDoesNotThrow(() -> BodyParserHelpers.handleResponseBody(reqCtx, content, "test"));

    verify(reqCtx, never()).getBlockResponseFunction();
  }

  private RecordingBlockResponseFunction givenBlockResponseFunction(boolean commitResult) {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
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

  /** Fake implementing only the abstract 5-arg method, so any default overload routes here. */
  private static final class RecordingBlockResponseFunction implements BlockResponseFunction {
    private final boolean commitResult;
    private int invocations;
    private TraceSegment segment;
    private int statusCode;
    private BlockingContentType templateType;

    RecordingBlockResponseFunction(boolean commitResult) {
      this.commitResult = commitResult;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      invocations++;
      this.segment = segment;
      this.statusCode = statusCode;
      this.templateType = templateType;
      return commitResult;
    }

    void assertCommittedOnce(TraceSegment expectedSegment) {
      assertEquals(1, invocations);
      assertSame(expectedSegment, segment);
      assertEquals(403, statusCode);
      assertEquals(BlockingContentType.AUTO, templateType);
    }
  }
}
