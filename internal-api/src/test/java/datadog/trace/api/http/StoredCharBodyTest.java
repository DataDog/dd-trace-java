package datadog.trace.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class StoredCharBodyTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON);

  private RequestContext requestContext;
  private BiFunction<RequestContext, StoredBodySupplier, Void> startCb;
  private BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb;
  private StoredCharBody storedCharBody;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    requestContext = mock(RequestContext.class);
    startCb = mock(BiFunction.class);
    endCb = mock(BiFunction.class);
    storedCharBody = new StoredCharBody(requestContext, startCb, endCb, 1);
  }

  @Test
  void basicTestWithNoBufferExtension() {
    @SuppressWarnings("unchecked")
    Flow<Void> flow = mock(Flow.class);

    storedCharBody.appendData("a");

    verify(startCb).apply(requestContext, storedCharBody);

    when(endCb.apply(requestContext, storedCharBody)).thenReturn(flow);
    storedCharBody.appendData((int) 'a');
    storedCharBody.appendData(repeat('a', 126), 0, 126);
    Flow<Void> resFlow = storedCharBody.maybeNotify();

    verify(endCb).apply(requestContext, storedCharBody);
    assertEquals(new String(repeat('a', 128)), storedCharBody.get().toString());
    assertSame(flow, resFlow);
  }

  @Test
  void hasACutoffAt128kChars() {
    storedCharBody.appendData("a");
    storedCharBody.appendData(new String(repeat('a', 128 * 1024))); // last ignored
    storedCharBody.appendData((int) 'a'); // ignored
    storedCharBody.appendData("a"); // ignored
    storedCharBody.appendData(new char[] {'a'}, 0, 1); // ignored

    verify(startCb).apply(requestContext, storedCharBody);
  }

  @Test
  void insertInvalidData() {
    storedCharBody.appendData(-1);

    assertEquals("", storedCharBody.get().toString());
  }

  @Test
  void insertEmptyRange() {
    storedCharBody.appendData(new char[0], 0, 0);

    assertEquals("", storedCharBody.get().toString());
  }

  @Test
  void exerciseMaybeNotifyAndGetOnEmptyObject() {
    storedCharBody.maybeNotify();

    InOrder ordered = inOrder(startCb, endCb);
    ordered.verify(startCb).apply(requestContext, storedCharBody);
    ordered.verify(endCb).apply(requestContext, storedCharBody);
    assertEquals("", storedCharBody.get().toString());
  }

  @ParameterizedTest(name = "commit succeeds: {0}")
  @ValueSource(booleans = {true, false})
  void maybeNotifyAndBlockCommitsBlockingResponseAndThrows(boolean commitResult) {
    TraceSegment traceSegment = mock(TraceSegment.class);
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(requestContext.getTraceSegment()).thenReturn(traceSegment);
    when(requestContext.getBlockResponseFunction()).thenReturn(brf);
    @SuppressWarnings("unchecked")
    Flow<Void> blockingFlow = mock(Flow.class);
    when(blockingFlow.getAction()).thenReturn(RBA);
    when(endCb.apply(requestContext, storedCharBody)).thenReturn(blockingFlow);

    storedCharBody.appendData("a");

    assertThrows(BlockingException.class, storedCharBody::maybeNotifyAndBlock);
    brf.assertCommittedOnce(traceSegment);
  }

  private static char[] repeat(char value, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, value);
    return chars;
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
