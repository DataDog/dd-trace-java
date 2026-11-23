package datadog.trace.instrumentation.java.net;

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
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the fail-closed blocking behavior of the SSRF RASP check: when the AppSec callback returns a
 * real {@link Flow.Action.RequestBlockingAction}, a {@link BlockingException} is always thrown,
 * whether or not a {@link BlockResponseFunction} is present and regardless of whether it managed to
 * commit the blocking response.
 */
class URLSinkCallSiteBlockingTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON);

  private AgentTracer.TracerAPI originalTracer;
  private SsrfModule originalSsrfModule;
  private TraceSegment traceSegment;
  private RequestContext reqCtx;
  private URL url;

  @BeforeEach
  void setUp() throws MalformedURLException {
    // keep the IAST side of the call site out of the way (a module left by another test would run)
    originalSsrfModule = InstrumentationBridge.SSRF;
    InstrumentationBridge.SSRF = null;

    originalTracer = AgentTracer.get();
    url = new URL("http://169.254.169.254/latest/meta-data/");
    traceSegment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);

    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);

    @SuppressWarnings("unchecked")
    Flow<Void> blockingFlow = mock(Flow.class);
    when(blockingFlow.getAction()).thenReturn(RBA);
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    when(callbackProvider.getCallback(EVENTS.httpClientRequest()))
        .thenReturn((ctx, request) -> blockingFlow);

    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
    InstrumentationBridge.SSRF = originalSsrfModule;
  }

  @Test
  void throwsWithoutBlockResponseFunction() {
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertThrows(BlockingException.class, () -> URLSinkCallSite.beforeOpenConnection(url));
  }

  @ParameterizedTest(name = "commit succeeds: {0}")
  @ValueSource(booleans = {true, false})
  void throwsWithBlockResponseFunction(boolean commitResult) {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertThrows(BlockingException.class, () -> URLSinkCallSite.beforeOpenConnection(url));

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
