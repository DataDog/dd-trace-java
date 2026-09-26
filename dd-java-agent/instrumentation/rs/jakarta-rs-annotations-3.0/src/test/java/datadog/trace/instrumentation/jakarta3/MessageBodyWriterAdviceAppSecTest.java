package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.jakarta3.MessageBodyWriterInstrumentation.MessageBodyWriterAdvice;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the current behavior of {@link MessageBodyWriterAdvice}: for an {@code application/json}
 * entity, a {@link Flow.Action.RequestBlockingAction} returned by the AppSec response body callback
 * makes the advice attempt to commit the blocking response and then throw a {@link
 * BlockingException} regardless of the commit result. Non-JSON media types never reach the
 * callback.
 */
class MessageBodyWriterAdviceAppSecTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private RequestContext reqCtx;
  private TraceSegment segment;
  private int callbackInvocations;
  private Object callbackEntity;

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

    BiFunction<RequestContext, Object, Flow<Void>> callback =
        (ctx, entity) -> {
          callbackInvocations++;
          callbackEntity = entity;
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

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void jsonEntityBlocksRegardlessOfCommitResult(boolean commitResult) {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    Object entity = new Object();

    BlockingException ex =
        assertThrows(
            BlockingException.class,
            () -> MessageBodyWriterAdvice.before(entity, MediaType.APPLICATION_JSON_TYPE, reqCtx));

    assertEquals("Blocked request (for MessageBodyWriter)", ex.getMessage());
    assertEquals(1, callbackInvocations);
    assertSame(entity, callbackEntity);
    brf.assertCommittedOnce(segment);
  }

  @Test
  void nonJsonMediaTypeSkipsCallback() {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(true);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    MessageBodyWriterAdvice.before("plain body", MediaType.TEXT_PLAIN_TYPE, reqCtx);

    assertEquals(0, callbackInvocations);
    assertEquals(0, brf.invocations);
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
