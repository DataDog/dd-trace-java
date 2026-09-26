package datadog.trace.bootstrap.instrumentation.api.java.lang;

import static datadog.trace.api.gateway.Events.EVENTS;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the fail-closed blocking behavior of the CMDI and SHI RASP checks: when the AppSec callback
 * returns a real {@link Flow.Action.RequestBlockingAction}, a {@link BlockingException} is always
 * thrown, whether or not a {@link BlockResponseFunction} is present and regardless of whether it
 * managed to commit the blocking response.
 */
class ProcessImplInstrumentationHelpersBlockingTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON);

  private static final String[] CMD_ARRAY = {"/bin/../usr/bin/reboot", "-f"};
  private static final String SHELL_CMD = "/bin/../usr/bin/reboot -f";

  private AgentTracer.TracerAPI originalTracer;
  private TraceSegment traceSegment;
  private RequestContext reqCtx;

  @BeforeEach
  void setUp() {
    // shiRaspCheck sets a ThreadLocal guard that makes cmdiRaspCheck a no-op until reset
    ProcessImplInstrumentationHelpers.resetCheckShi();

    originalTracer = AgentTracer.get();
    traceSegment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);

    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);

    @SuppressWarnings("unchecked")
    Flow<Void> blockingFlow = mock(Flow.class);
    when(blockingFlow.getAction()).thenReturn(RBA);
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    when(callbackProvider.getCallback(EVENTS.execCmd())).thenReturn((ctx, cmd) -> blockingFlow);
    when(callbackProvider.getCallback(EVENTS.shellCmd())).thenReturn((ctx, cmd) -> blockingFlow);

    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
    ProcessImplInstrumentationHelpers.resetCheckShi();
  }

  @Test
  void cmdiThrowsWithoutBlockResponseFunction() {
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertThrows(
        BlockingException.class, () -> ProcessImplInstrumentationHelpers.cmdiRaspCheck(CMD_ARRAY));
  }

  @ParameterizedTest(name = "commit succeeds: {0}")
  @ValueSource(booleans = {true, false})
  void cmdiThrowsWithBlockResponseFunction(boolean commitResult) {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertThrows(
        BlockingException.class, () -> ProcessImplInstrumentationHelpers.cmdiRaspCheck(CMD_ARRAY));

    brf.assertCommittedOnce(traceSegment);
  }

  @Test
  void shiThrowsWithoutBlockResponseFunction() {
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertThrows(
        BlockingException.class, () -> ProcessImplInstrumentationHelpers.shiRaspCheck(SHELL_CMD));
  }

  @ParameterizedTest(name = "commit succeeds: {0}")
  @ValueSource(booleans = {true, false})
  void shiThrowsWithBlockResponseFunction(boolean commitResult) {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertThrows(
        BlockingException.class, () -> ProcessImplInstrumentationHelpers.shiRaspCheck(SHELL_CMD));

    brf.assertCommittedOnce(traceSegment);
  }

  /**
   * Hand-written fake that only implements the abstract 5-arg method, so it records the commit
   * whichever {@code tryCommitBlockingResponse} overload the production code calls.
   */
  private static final class RecordingBlockResponseFunction implements BlockResponseFunction {
    private final boolean commitResult;
    private int calls;
    private TraceSegment lastSegment;
    private int lastStatusCode;
    private BlockingContentType lastTemplateType;

    private RecordingBlockResponseFunction(boolean commitResult) {
      this.commitResult = commitResult;
    }

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
      return commitResult;
    }

    private void assertCommittedOnce(TraceSegment expectedSegment) {
      assertEquals(1, calls);
      assertSame(expectedSegment, lastSegment);
      assertEquals(RBA.getStatusCode(), lastStatusCode);
      assertEquals(RBA.getBlockingContentType(), lastTemplateType);
    }
  }
}
