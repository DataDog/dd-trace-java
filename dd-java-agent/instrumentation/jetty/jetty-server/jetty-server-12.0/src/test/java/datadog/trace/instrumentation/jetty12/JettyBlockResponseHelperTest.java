package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyFakes.fake;
import static datadog.trace.instrumentation.jetty12.JettyFakes.proxy;
import static datadog.trace.instrumentation.jetty12.JettyFakes.toBytes;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.BLOCKING;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty12.JettyFakes.FakeRequest;
import datadog.trace.instrumentation.jetty12.JettyFakes.FakeResponse;
import datadog.trace.instrumentation.jetty12.JettyFakes.FakeSegment;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Unit tests for {@link JettyBlockResponseHelper} and {@link JettyBlockResponseFunction}. */
class JettyBlockResponseHelperTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private int blockFailures;

  @TableTest({
    "Scenario            | Content Type | Accept                             | Expected Type",
    "json                | JSON         | text/html                          | JSON         ",
    "html                | HTML         | application/json                   | HTML         ",
    "auto without accept | AUTO         |                                    | JSON         ",
    "auto prefers html   | AUTO         | text/html                          | HTML         ",
    "auto prefers json   | AUTO         | 'text/html;q=0.5,application/json' | JSON         "
  })
  void writesTemplateWithContentHeaders(
      BlockingContentType contentType, String accept, TemplateType expectedType) {
    FakeRequest request = new FakeRequest("GET", accept);
    FakeResponse response = new FakeResponse();
    response.headers.put("X-App-Header", "app");
    FakeSegment segment = new FakeSegment();

    boolean committed =
        new JettyBlockResponseFunction(request.proxy, response.proxy)
            .tryCommitBlockingResponse(
                segment.proxy, 418, contentType, singletonMap("X-Extra", "value"), "rid-1");

    assertTrue(committed);
    byte[] expectedBody = BlockingActionHelper.getTemplate(expectedType, "rid-1");
    assertEquals(Boolean.TRUE, request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(1, response.resets);
    assertEquals(418, response.status);
    assertNull(response.headers.get("X-App-Header"), "reset must clear previous headers");
    assertEquals("value", response.headers.get("X-Extra"));
    assertEquals(
        BlockingActionHelper.getContentType(expectedType),
        response.headers.get(HttpHeader.CONTENT_TYPE));
    assertEquals(
        String.valueOf(expectedBody.length), response.headers.get(HttpHeader.CONTENT_LENGTH));
    assertEquals(1, response.writes.size());
    assertTrue(response.lastFlags.get(0));
    assertArrayEquals(expectedBody, response.writes.get(0));
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void invalidStatusFallsBackToDefault() {
    FakeResponse response = new FakeResponse();
    assertTrue(commit(brf(new FakeRequest("GET", null), response), new FakeSegment(), 42));
    assertEquals(403, response.status);
  }

  @Test
  void noneKeepsOnlyStatusAndExtraHeaders() {
    FakeResponse response = new FakeResponse();
    FakeSegment segment = new FakeSegment();

    boolean committed =
        brf(new FakeRequest("GET", "text/html"), response)
            .tryCommitBlockingResponse(
                segment.proxy,
                301,
                BlockingContentType.NONE,
                singletonMap("Location", "https://example.com/"),
                null);

    assertTrue(committed);
    assertEquals(301, response.status);
    assertEquals("https://example.com/", response.headers.get("Location"));
    assertEquals(1, response.headers.size());
    assertEquals(1, response.writes.size());
    assertEquals(0, response.writes.get(0).length);
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void headRequestHasNoBody() {
    FakeResponse response = new FakeResponse();
    assertTrue(commit(brf(new FakeRequest("HEAD", "application/json"), response)));
    assertEquals(403, response.status);
    assertNull(response.headers.get(HttpHeader.CONTENT_TYPE));
    assertNull(response.headers.get(HttpHeader.CONTENT_LENGTH));
    assertEquals(0, response.writes.get(0).length);
  }

  @Test
  void prepareReturnsNullWhenCommitted() {
    FakeRequest request = new FakeRequest("GET", null);
    FakeResponse response = new FakeResponse();
    response.committed = true;

    assertNull(
        JettyBlockResponseHelper.prepareBlockResponse(
            request.proxy, response.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(0, response.resets);
  }

  @Test
  void prepareDoesNotWrite() {
    FakeResponse response = new FakeResponse();
    ByteBuffer body =
        JettyBlockResponseHelper.prepareBlockResponse(
            new FakeRequest("GET", null).proxy,
            response.proxy,
            403,
            BlockingContentType.JSON,
            emptyMap(),
            null);

    assertArrayEquals(BlockingActionHelper.getTemplate(TemplateType.JSON, null), toBytes(body));
    assertTrue(response.writes.isEmpty());
  }

  @TableTest({
    "Scenario     | Failure      | Resets",
    "committed    | committed    | 0     ",
    "reset throws | reset        | 0     ",
    "write throws | write throws | 2     "
  })
  void failedCommitIsNotBlockedAndReported(String failure, int resets) {
    FakeRequest request = new FakeRequest("GET", null);
    FakeResponse response = new FakeResponse();
    switch (failure) {
      case "committed":
        response.committed = true;
        break;
      case "reset":
        response.resetFailure = new IllegalStateException("committed");
        break;
      case "write throws":
        response.writeThrows = new WritePendingException();
        break;
      default:
        throw new IllegalArgumentException(failure);
    }
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf = brf(request, response);

    assertFalse(brf.tryCommitBlockingResponse(requestContext(segment), RBA));

    assertEquals(resets, response.resets);
    assertTrue(response.writes.isEmpty());
    assertEquals(0, segment.effectivelyBlockedCalls);
    assertEquals(1, blockFailures);
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertFalse(brf.isBlockingResponseInitiated(), "a failed attempt must release the claim");
  }

  @Test
  void synchronousWriteFailureRestoresResponse() {
    FakeRequest request = new FakeRequest("GET", null);
    FakeResponse response = new FakeResponse();
    response.writeThrows = new WritePendingException();

    assertFalse(
        brf(request, response)
            .tryCommitBlockingResponse(
                new FakeSegment().proxy,
                403,
                BlockingContentType.JSON,
                singletonMap("X-Extra", "value"),
                null));

    assertEquals(2, response.resets, "prepared, then reset again before failing open");
    assertEquals(0, response.status);
    assertEquals(0, response.headers.size(), "no blocking headers must leak to the app");
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void restoreSkipsResetOnCommittedResponse() {
    FakeRequest request = new FakeRequest("GET", null);
    request.attributes.put(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    FakeResponse response = new FakeResponse();
    response.committed = true;

    JettyBlockResponseHelper.restoreAfterFailedBlock(request.proxy, response.proxy);

    assertEquals(0, response.resets);
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void restoreIgnoresResetFailure() {
    FakeRequest request = new FakeRequest("GET", null);
    request.attributes.put(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    FakeResponse response = new FakeResponse();
    response.resetFailure = new IllegalStateException("committed");

    JettyBlockResponseHelper.restoreAfterFailedBlock(request.proxy, response.proxy);

    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void templateFailureLeavesResponseUntouched() {
    FakeRequest request = new FakeRequest("GET", null);
    request.headersFailure = new IllegalStateException("headers");
    FakeResponse response = new FakeResponse();

    assertThrows(
        IllegalStateException.class,
        () ->
            JettyBlockResponseHelper.prepareBlockResponse(
                request.proxy, response.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(0, response.resets);
  }

  @Test
  void resetFailureLeavesNoIgnoreCommitAttribute() {
    FakeRequest request = new FakeRequest("GET", null);
    FakeResponse response = new FakeResponse();
    response.resetFailure = new IllegalStateException("committed");

    assertThrows(
        IllegalStateException.class,
        () ->
            JettyBlockResponseHelper.prepareBlockResponse(
                request.proxy, response.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void returnsBeforeTheWriteCompletesAndMarksOnSuccess() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), response);

    assertTrue(commit(brf, segment), "the write was accepted");
    assertNotNull(response.pending, "the write callback is still pending");
    assertSame(
        NON_BLOCKING, response.pending.getInvocationType(), "no wrapper: the type of onWritten");
    assertEquals(0, segment.effectivelyBlockedCalls);
    assertTrue(brf.isBlockingResponseInitiated());

    response.pending.succeeded();

    assertEquals(1, segment.effectivelyBlockedCalls);
    assertTrue(brf.isBlockingResponseInitiated());
  }

  @Test
  void asynchronousWriteFailureReportsAndReleasesClaim() {
    FakeRequest request = new FakeRequest("GET", null);
    request.attributes.put(DD_CONTEXT_ATTRIBUTE, context(requestContext(new FakeSegment())));
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf = brf(request, response);

    assertTrue(commit(brf, segment));
    response.pending.failed(new IOException("broken pipe"));

    assertEquals(0, segment.effectivelyBlockedCalls);
    assertEquals(1, blockFailures);
    assertFalse(brf.isBlockingResponseInitiated());

    response.completeWrites = true;
    assertTrue(commit(brf, segment));
    assertEquals(2, response.writes.size(), "the next call must write again");
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void asynchronousWriteFailureWithoutRequestContextReleasesClaim() {
    FakeResponse response = new FakeResponse();
    response.writeFailure = new IOException("broken pipe");
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), response);

    assertTrue(commit(brf, segment), "the write was accepted, it failed in its callback");

    assertEquals(0, segment.effectivelyBlockedCalls);
    assertFalse(brf.isBlockingResponseInitiated());
  }

  @Test
  void effectivelyBlockedFailureKeepsSuccess() {
    FakeSegment segment = new FakeSegment();
    segment.failure = new IllegalStateException("segment finished");

    assertTrue(commit(brf(new FakeRequest("GET", null), new FakeResponse()), segment));
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void laterEvaluationDoesNotWriteAgain() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), response);

    assertTrue(commit(brf, segment));
    assertTrue(commit(brf, segment), "another evaluation owns the block");

    assertEquals(1, response.writes.size());
    assertEquals(1, response.resets);
  }

  @Test
  void alreadyClaimedFunctionDoesNotWrite() {
    FakeResponse response = new FakeResponse();
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), response);
    assertTrue(brf.tryClaim());

    assertTrue(commit(brf, new FakeSegment()));

    assertTrue(response.writes.isEmpty());
    assertEquals(0, response.resets);
  }

  @Test
  void claimIsExclusiveUntilReleased() {
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), new FakeResponse());

    assertTrue(brf.tryClaim());
    assertFalse(brf.tryClaim());
    brf.release();
    assertTrue(brf.tryClaim());
  }

  @Test
  void concurrentClaimsHaveASingleWinner() throws Exception {
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), new FakeResponse());
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Boolean>> claims = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        claims.add(
            executor.submit(() -> {
              start.await();
              return brf.tryClaim();
            }));
      }
      start.countDown();
      int winners = 0;
      for (Future<Boolean> claim : claims) {
        if (claim.get(10, SECONDS)) {
          winners++;
        }
      }
      assertEquals(1, winners);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void deferredCompletionIsParkedFromInsideTheWriteAndReplayedAfterMarking() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    DeferredFixture f = new DeferredFixture(response);
    IllegalStateException blocking = new IllegalStateException("blocked");
    // the write is already in flight when write() runs
    response.onWrite = () -> f.wrapper.failed(blocking);

    assertTrue(commit(f.brf, f.segment));

    assertTrue(f.events.isEmpty(), "the completion must wait for the pending write");
    response.pending.succeeded();

    assertEquals(asList("effectivelyBlocked", "failed"), f.events);
    assertSame(blocking, f.failure, "the parked outcome is replayed unchanged");
  }

  @Test
  void deferredCompletionIsReplayedWhenTheWriteCompletesInsideWrite() {
    FakeResponse response = new FakeResponse();
    DeferredFixture f = new DeferredFixture(response);
    response.onWrite = f.wrapper::succeeded;

    assertTrue(commit(f.brf, f.segment));

    assertEquals(asList("effectivelyBlocked", "succeeded"), f.events);
  }

  @Test
  void idleDeferredCompletionIsForwardedImmediately() {
    FakeResponse response = new FakeResponse();
    DeferredFixture f = new DeferredFixture(response);

    assertTrue(commit(f.brf, f.segment));
    assertEquals(asList("effectivelyBlocked"), f.events);

    f.wrapper.succeeded();

    assertEquals(asList("effectivelyBlocked", "succeeded"), f.events);
  }

  @Test
  void deferredCompletionIsReplayedBeforeTheClaimIsReleasedOnWriteFailure() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    DeferredFixture f = new DeferredFixture(response);
    IllegalStateException blocking = new IllegalStateException("blocked");

    assertTrue(commit(f.brf, f.segment));
    f.wrapper.failed(blocking);
    assertTrue(f.events.isEmpty());

    response.pending.failed(new IOException("broken pipe"));

    assertEquals(asList("failed"), f.events);
    assertSame(blocking, f.failure);
    assertTrue(f.claimedOnCompletion, "the write must be finished before the claim is released");
    assertFalse(f.brf.isBlockingResponseInitiated());
  }

  @Test
  void retryAfterFailedWriteParksTheCompletionAgain() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    DeferredFixture f = new DeferredFixture(response);

    assertTrue(commit(f.brf, f.segment));
    response.pending.failed(new IOException("broken pipe"));
    assertFalse(f.brf.isBlockingResponseInitiated());

    assertTrue(commit(f.brf, f.segment), "the retry writes again");
    assertEquals(2, response.writes.size());
    f.wrapper.succeeded();
    assertTrue(f.events.isEmpty(), "the retried write is in flight");

    response.pending.succeeded();

    assertEquals(asList("effectivelyBlocked", "succeeded"), f.events);
  }

  @Test
  void deferredCompletionIsReplayedBeforeTheClaimIsReleasedOnSynchronousWriteThrow() {
    FakeResponse response = new FakeResponse();
    response.writeThrows = new WritePendingException();
    DeferredFixture f = new DeferredFixture(response);
    response.onWrite = f.wrapper::succeeded;

    assertFalse(commit(f.brf, f.segment));

    assertEquals(asList("succeeded"), f.events);
    assertTrue(f.claimedOnCompletion, "the write must be finished before the claim is released");
    assertFalse(f.brf.isBlockingResponseInitiated());
  }

  @Test
  void synchronousWriteThrowLeavesTheDeferredCompletionIdle() {
    FakeResponse response = new FakeResponse();
    response.writeThrows = new WritePendingException();
    DeferredFixture f = new DeferredFixture(response);

    assertFalse(commit(f.brf, f.segment));
    f.wrapper.succeeded();

    assertEquals(asList("succeeded"), f.events, "no write in flight, forwarded immediately");
  }

  @Test
  void deferCompletionIsInstalledOnce() {
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), new FakeResponse());
    Callback second = fake(Callback.class);

    assertSame(NON_BLOCKING, brf.writeCallbackType(NON_BLOCKING), "no wrapper installed yet");
    Callback wrapper = brf.deferCompletion(blockingCallback());

    assertInstanceOf(JettyDeferredCompletion.class, wrapper);
    assertSame(BLOCKING, brf.writeCallbackType(NON_BLOCKING), "combined with the wrapper type");
    assertSame(second, brf.deferCompletion(second), "a second install keeps the first wrapper");
    assertSame(BLOCKING, brf.writeCallbackType(NON_BLOCKING), "still the first wrapper type");
  }

  @Test
  void writeCallbackTypeIsCombinedWithTheInstalledWrapper() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    JettyBlockResponseFunction brf = brf(new FakeRequest("GET", null), response);
    brf.deferCompletion(blockingCallback());

    assertTrue(commit(brf));

    assertSame(
        BLOCKING,
        response.pending.getInvocationType(),
        "onWritten is NOOP, but the write callback may replay the BLOCKING Handler completion");
  }

  /** A callback keeping the default {@code BLOCKING} invocation type. */
  private static Callback blockingCallback() {
    return new Callback() {};
  }

  /** A {@link JettyBlockResponseFunction} with an installed {@link JettyDeferredCompletion}. */
  private static final class DeferredFixture {
    /** Records {@code effectivelyBlocked()} calls and delegate completions, in order. */
    final List<String> events = new ArrayList<>();

    final FakeSegment segment = new FakeSegment();
    final JettyBlockResponseFunction brf;
    final JettyDeferredCompletion wrapper;
    Throwable failure;
    boolean claimedOnCompletion;

    DeferredFixture(FakeResponse response) {
      brf = new JettyBlockResponseFunction(new FakeRequest("GET", null).proxy, response.proxy);
      segment.onEffectivelyBlocked = () -> events.add("effectivelyBlocked");
      Callback delegate =
          proxy(
              Callback.class,
              (name, args) -> {
                switch (name) {
                  case "succeeded":
                    events.add(name);
                    claimedOnCompletion = brf.isBlockingResponseInitiated();
                    return null;
                  case "failed":
                    events.add(name);
                    failure = (Throwable) args[0];
                    claimedOnCompletion = brf.isBlockingResponseInitiated();
                    return null;
                  case "getInvocationType":
                    return NON_BLOCKING;
                  default:
                    return null;
                }
              });
      wrapper = (JettyDeferredCompletion) brf.deferCompletion(delegate);
    }
  }

  private static JettyBlockResponseFunction brf(FakeRequest request, FakeResponse response) {
    return new JettyBlockResponseFunction(request.proxy, response.proxy);
  }

  private static boolean commit(JettyBlockResponseFunction brf) {
    return commit(brf, new FakeSegment());
  }

  private static boolean commit(JettyBlockResponseFunction brf, FakeSegment segment) {
    return commit(brf, segment, 403);
  }

  private static boolean commit(JettyBlockResponseFunction brf, FakeSegment segment, int status) {
    return brf.tryCommitBlockingResponse(
        segment.proxy, status, BlockingContentType.AUTO, emptyMap(), null);
  }

  /** A request context counting {@code block_failure} reports. */
  private RequestContext requestContext(FakeSegment segment) {
    AppSecContext appSecCtx =
        proxy(
            AppSecContext.class,
            (name, args) -> {
              if ("reportBlockFailure".equals(name)) {
                blockFailures++;
              }
              return null;
            });
    return proxy(
        RequestContext.class,
        (name, args) -> {
          switch (name) {
            case "getTraceSegment":
              return segment.proxy;
            case "getData":
              return args[0] == RequestContextSlot.APPSEC ? appSecCtx : null;
            default:
              return null;
          }
        });
  }

  private static Context context(RequestContext reqCtx) {
    AgentSpan span =
        proxy(AgentSpan.class, (name, args) -> "getRequestContext".equals(name) ? reqCtx : null);
    return proxy(Context.class, (name, args) -> "get".equals(name) ? span : null);
  }
}
