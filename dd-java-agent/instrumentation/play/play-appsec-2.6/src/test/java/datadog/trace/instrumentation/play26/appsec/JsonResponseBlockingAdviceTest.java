package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.play26.appsec.ResultsStatusInstrumentation.ResultsStatusApplyAdvice;
import datadog.trace.instrumentation.play26.appsec.StatusHeaderInstrumentation.StatusHeaderSendJsonAdvice;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.api.libs.json.JsValue;
import play.api.libs.json.Json$;
import play.mvc.StatusHeader;

/**
 * Pins the CURRENT behavior of {@link ResultsStatusApplyAdvice} and {@link
 * StatusHeaderSendJsonAdvice} (also applied to Play 2.7+ via the {@code play26Plus} muzzle
 * directive): when the AppSec response body callback returns a {@link
 * Flow.Action.RequestBlockingAction} and a {@link BlockResponseFunction} is present, the advice
 * attempts to commit the blocking response and then throws a {@link BlockingException}
 * unconditionally, ignoring the commit result.
 *
 * <p><b>Deliberate tripwire:</b> PR #12601 (APPSEC-70201) intentionally changes these advices to
 * throw only when the commit succeeds. The {@code commitResult = false} cases are EXPECTED to fail
 * once that PR is rebased on top of this test. That failure is the signal to consciously review the
 * behavior change, not a regression to fix by reverting #12601.
 */
class JsonResponseBlockingAdviceTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private RequestContext reqCtx;
  private TraceSegment segment;

  @BeforeEach
  void setup() {
    segment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(segment);
    // @RequiresRequestContext rewrites the compiled advice to read the request context from the
    // active span and to bail out unless AppSec data is present
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(new Object());
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);

    BiFunction<RequestContext, Object, Flow<Void>> callback = (ctx, body) -> blockingFlow();
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

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void resultsStatusApplyThrowsRegardlessOfCommitResult(boolean commitResult) {
    RecordingBlockResponseFunction brf = givenBlockResponseFunction(commitResult);
    JsValue content = Json$.MODULE$.parse("{\"key\":\"value\"}");

    BlockingException ex =
        assertThrows(
            BlockingException.class, () -> ResultsStatusApplyAdvice.after(content, reqCtx));

    assertEquals("Blocked request (for Results$Status/apply)", ex.getMessage());
    brf.assertCommittedOnce(segment);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void statusHeaderSendJsonThrowsRegardlessOfCommitResult(boolean commitResult) {
    RecordingBlockResponseFunction brf = givenBlockResponseFunction(commitResult);
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
