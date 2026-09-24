package datadog.trace.instrumentation.vertx_5_0.server;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the blocking behavior of {@link FileUploadHelper#commitBlockingResponse}, shared by the
 * multipart filenames and file-content callbacks of {@link RoutingContextFilenamesAdvice}: on a
 * {@link Flow.Action.RequestBlockingAction} it commits the blocking response and returns a {@link
 * BlockingException} carrying the given reason, but fails open (returns {@code null}) when there is
 * no {@link BlockResponseFunction}.
 *
 * <p>vertx-web 3.4 and 4.0 have identical copies of this helper, each covered by its own test.
 */
class FileUploadHelperTest {

  private static final String FILENAMES_REASON = "Blocked request (multipart file upload)";
  private static final String CONTENT_REASON = "Blocked request (file content)";
  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON);

  private TraceSegment traceSegment;
  private RequestContext reqCtx;
  private Flow<Void> flow;
  private RequestContext receivedCtx;
  private List<String> receivedData;
  private BiFunction<RequestContext, List<String>, Flow<Void>> cb;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    traceSegment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    flow = mock(Flow.class);
    cb =
        (ctx, data) -> {
          receivedCtx = ctx;
          receivedData = data;
          return flow;
        };
  }

  @Test
  void commitsAndReturnsExceptionOnBlockingFilenames() {
    assertCommitsAndReturnsException(asList("a.txt", "b.txt"), FILENAMES_REASON);
  }

  @Test
  void commitsAndReturnsExceptionOnBlockingFilesContent() {
    assertCommitsAndReturnsException(singletonList("file content"), CONTENT_REASON);
  }

  @Test
  void failsOpenWithoutBlockResponseFunction() {
    List<String> data = singletonList("a.txt");
    when(flow.getAction()).thenReturn(RBA);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertNull(FileUploadHelper.commitBlockingResponse(cb, reqCtx, data, FILENAMES_REASON));

    assertSame(reqCtx, receivedCtx);
    assertSame(data, receivedData);
  }

  @Test
  void doesNotCommitWithoutBlockingAction() {
    List<String> data = singletonList("a.txt");
    when(flow.getAction()).thenReturn(Flow.Action.Noop.INSTANCE);
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction();
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertNull(FileUploadHelper.commitBlockingResponse(cb, reqCtx, data, FILENAMES_REASON));

    assertSame(data, receivedData);
    assertEquals(0, brf.calls);
  }

  private void assertCommitsAndReturnsException(List<String> data, String reason) {
    when(flow.getAction()).thenReturn(RBA);
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction();
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    BlockingException exception = FileUploadHelper.commitBlockingResponse(cb, reqCtx, data, reason);

    assertNotNull(exception);
    assertEquals(reason, exception.getMessage());
    assertSame(reqCtx, receivedCtx);
    assertSame(data, receivedData);
    brf.assertCommittedOnce(traceSegment);
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
