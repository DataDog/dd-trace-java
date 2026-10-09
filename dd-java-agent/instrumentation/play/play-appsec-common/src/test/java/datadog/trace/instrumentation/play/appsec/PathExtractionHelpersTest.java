package datadog.trace.instrumentation.play.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
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
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the CURRENT fail-closed behavior of {@link
 * PathExtractionHelpers#callRequestPathParamsCallback}: when the AppSec path params callback
 * returns a {@link Flow.Action.RequestBlockingAction}, a non-null {@link BlockingException} is
 * always returned, whether or not a {@link BlockResponseFunction} is present and regardless of
 * whether committing the blocking response succeeds.
 *
 * <p><b>Deliberate tripwire:</b> PR #12601 (APPSEC-70201) intentionally changes this to fail-open
 * ({@code if (brf == null) return null;}) and ships its own {@code PathExtractionHelpersTest} at
 * this same path. This test is EXPECTED to fail (or conflict) once that PR is rebased on top of it.
 * That failure is the intended signal to consciously review the behavior change, not a regression
 * to fix by reverting #12601. When rebasing, replace this file with the #12601 version once the
 * fail-open change has been reviewed and accepted.
 */
class PathExtractionHelpersTest {

  private static final String ORIGIN = "test-origin";
  private static final Map<String, Object> PARAMS = singletonMap("id", "1");
  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private RequestContext reqCtx;
  private TraceSegment segment;
  private CallbackProvider cbp;

  @BeforeEach
  void setup() {
    segment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(segment);

    cbp = mock(CallbackProvider.class);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  /**
   * Tripwire: fail-closed without a BRF. #12601 makes this return null (fail-open), so this test is
   * expected to fail after rebasing on #12601.
   */
  @Test
  void returnsBlockingExceptionWhenBlockingActionAndNoBlockResponseFunction() {
    givenCallbackReturning(RBA);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    BlockingException result = call(PARAMS);

    assertNotNull(result);
    assertEquals("Blocked request (for " + ORIGIN + ")", result.getMessage());
  }

  /**
   * Fail-closed with a BRF: a {@link BlockingException} is returned and the commit is attempted,
   * independently of the commit result.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void returnsBlockingExceptionWhenBlockingActionRegardlessOfCommitResult(boolean commitResult) {
    givenCallbackReturning(RBA);
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    BlockingException result = call(PARAMS);

    assertNotNull(result);
    assertEquals(1, brf.invocations);
    assertSame(segment, brf.segment);
    assertEquals(403, brf.statusCode);
    assertEquals(BlockingContentType.AUTO, brf.templateType);
  }

  @Test
  void returnsNullWhenActionIsNotBlocking() {
    givenCallbackReturning(Flow.Action.Noop.INSTANCE);

    assertNull(call(PARAMS));
  }

  @Test
  void returnsNullWhenNoCallbackRegistered() {
    when(cbp.getCallback(EVENTS.requestPathParams())).thenReturn(null);

    assertNull(call(PARAMS));
  }

  @Test
  void returnsNullWhenCallbackThrows() {
    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
        (ctx, params) -> {
          throw new IllegalStateException("boom");
        };
    when(cbp.getCallback(EVENTS.requestPathParams())).thenReturn(callback);

    assertNull(call(PARAMS));
  }

  @Test
  void returnsNullForNullParams() {
    givenCallbackReturning(RBA);

    assertNull(call(null));
  }

  @Test
  void returnsNullForEmptyParams() {
    givenCallbackReturning(RBA);

    assertNull(call(emptyMap()));
  }

  private BlockingException call(Map<String, Object> params) {
    return PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, params, ORIGIN);
  }

  private void givenCallbackReturning(Flow.Action action) {
    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
        (ctx, params) -> flowWith(action);
    when(cbp.getCallback(EVENTS.requestPathParams())).thenReturn(callback);
  }

  private static Flow<Void> flowWith(Flow.Action action) {
    return new Flow<Void>() {
      @Override
      public Action getAction() {
        return action;
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
    int invocations;
    TraceSegment segment;
    int statusCode;
    BlockingContentType templateType;

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
  }
}
