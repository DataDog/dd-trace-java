package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyFakes.fake;
import static datadog.trace.instrumentation.jetty12.JettyFakes.proxy;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty12.JettyFakes.FakeRequest;
import datadog.trace.instrumentation.jetty12.JettyFakes.FakeResponse;
import java.io.IOException;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Unit tests for {@link JettyBlockResponseHelper#tryBlockRequest}. */
class JettyRequestBlockingTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final TraceSegment segment = fake(TraceSegment.class);
  private int callbackSucceeded;
  private int callbackFailed;
  private int blockFailures;
  private int commits;
  private Flow.Action.RequestBlockingAction committedRba;

  @Test
  void blocksAndCompletesCallbackWhenCommitSucceeds() {
    boolean blocked = tryBlock(true, true, true, true, brf(true));

    assertTrue(blocked);
    assertEquals(1, commits);
    assertEquals(BlockingContentType.AUTO, committedRba.getBlockingContentType());
    assertEquals(403, committedRba.getStatusCode());
    assertEquals(1, callbackSucceeded);
    assertEquals(0, callbackFailed);
    assertEquals(0, blockFailures);
  }

  @TableTest({
    "Scenario      | BRF Outcome",
    "commit fails  | false      ",
    "commit throws | throws     "
  })
  void failsOpenAndReportsWhenCommitFails(String brfOutcome) {
    boolean blocked = tryBlock(true, true, true, true, brf(brfOutcome));

    assertFalse(blocked);
    assertEquals(1, commits);
    assertEquals(0, callbackSucceeded);
    assertEquals(0, callbackFailed);
    assertEquals(1, blockFailures);
  }

  @TableTest({
    "Scenario           | Context | Span  | Blocking Action | Request Context | BRF  ",
    "no context         | false   | true  | true            | true            | true ",
    "no span            | true    | false | true            | true            | true ",
    "no blocking action | true    | true  | false           | true            | true ",
    "no request context | true    | true  | true            | false           | true ",
    "no BRF             | true    | true  | true            | true            | false"
  })
  void doesNothingWhenNothingToBlock(
      boolean context, boolean span, boolean blockingAction, boolean requestContext, boolean brf) {
    boolean blocked =
        tryBlock(context, span, blockingAction, requestContext, brf ? brf(true) : null);

    assertFalse(blocked);
    assertEquals(0, commits);
    assertEquals(0, callbackSucceeded);
    assertEquals(0, callbackFailed);
    assertEquals(0, blockFailures);
  }

  @Test
  void stillBlocksWhenCallbackThrows() {
    Callback throwingCallback =
        proxy(
            Callback.class,
            (name, args) -> {
              throw new IllegalStateException("already completed");
            });

    boolean blocked =
        JettyBlockResponseHelper.tryBlockRequest(
            request(true, true, true, true, brf(true)), throwingCallback);

    assertTrue(blocked, "the blocking response was written, the handler chain must not run");
  }

  @Test
  void commitReturnsFalseWithoutReportingWhenThereIsNoBrf() {
    assertFalse(JettyBlockResponseHelper.tryCommitBlockingResponse(requestContext(null), RBA));
    assertFalse(JettyBlockResponseHelper.tryCommitBlockingResponse(null, RBA));

    assertEquals(0, commits);
    assertEquals(0, blockFailures, "nothing was attempted, nothing to report");
  }

  @Test
  void commitReturnsTrueWhenBrfCommits() {
    assertTrue(JettyBlockResponseHelper.tryCommitBlockingResponse(requestContext(brf(true)), RBA));

    assertEquals(1, commits);
    assertEquals(0, blockFailures);
  }

  @TableTest({
    "Scenario   | BRF Outcome",
    "BRF fails  | false      ",
    "BRF throws | throws     "
  })
  void commitReturnsFalseAndReportsWhenBrfFails(String brfOutcome) {
    assertFalse(
        JettyBlockResponseHelper.tryCommitBlockingResponse(requestContext(brf(brfOutcome)), RBA));

    assertEquals(1, commits);
    assertEquals(1, blockFailures);
  }

  @Test
  void jettyBrfCompletesCallbackOnlyAfterWriteSucceeds() {
    JettyBrfFixture f = new JettyBrfFixture();

    assertTrue(f.tryBlock());

    assertEquals(1, f.response.writes.size(), "the blocking response write was issued");
    assertTrue(f.response.lastFlags.get(0));
    assertNotNull(f.response.pending);
    assertSame(
        InvocationType.EITHER,
        f.response.pending.getInvocationType(),
        "the write callback keeps the invocation type of the Jetty callback");
    assertTrue(f.events.isEmpty(), "the Jetty callback must wait for the write");
    assertTrue(f.brf.isBlockingResponseInitiated());

    f.response.pending.succeeded();

    assertEquals(asList("effectivelyBlocked", "succeeded"), f.events);
    assertEquals(0, blockFailures);
    assertTrue(f.brf.isBlockingResponseInitiated());
  }

  @Test
  void jettyBrfWriteFailureReportsAndFailsCallback() {
    JettyBrfFixture f = new JettyBrfFixture();
    IOException failure = new IOException("broken pipe");

    assertTrue(f.tryBlock());
    f.response.pending.failed(failure);

    assertEquals(asList("failed"), f.events);
    assertSame(failure, f.callbackFailure);
    assertEquals(1, blockFailures);
    assertFalse(f.brf.isBlockingResponseInitiated(), "a failed write must clear the guard");
  }

  @TableTest({
    "Scenario     | Failure     ",
    "committed    | committed   ",
    "reset throws | reset throws",
    "write throws | write throws"
  })
  void jettyBrfFailsOpenWhenNothingWasWritten(String failure) {
    JettyBrfFixture f = new JettyBrfFixture();
    switch (failure) {
      case "committed":
        f.response.committed = true;
        break;
      case "reset throws":
        f.response.resetFailure = new IllegalStateException("committed");
        break;
      case "write throws":
        f.response.writeThrows = new WritePendingException();
        break;
      default:
        throw new IllegalArgumentException(failure);
    }

    assertFalse(f.tryBlock());

    assertTrue(f.response.writes.isEmpty());
    assertTrue(f.events.isEmpty(), "the handler chain completes the Jetty callback");
    assertEquals(1, blockFailures);
    assertFalse(f.brf.isBlockingResponseInitiated());
    assertNull(f.request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
  }

  @Test
  void jettyBrfWriteThrowRestoresResponseBeforeFailingOpen() {
    JettyBrfFixture f = new JettyBrfFixture();
    f.response.headers.put("X-App-Header", "app");
    f.response.writeThrows = new WritePendingException();

    assertFalse(f.tryBlock());

    assertEquals(2, f.response.resets, "prepared, then reset again before failing open");
    assertEquals(0, f.response.status);
    assertEquals(0, f.response.headers.size());
    assertNull(f.request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(1, blockFailures);
  }

  @Test
  void alreadyClaimedJettyBrfCompletesCallbackWithoutWriting() {
    JettyBrfFixture f = new JettyBrfFixture();
    f.brf.markBlockingResponseInitiated();

    assertTrue(f.tryBlock());

    assertTrue(f.response.writes.isEmpty(), "another evaluation owns the block");
    assertEquals(asList("succeeded"), f.events);
    assertEquals(0, blockFailures);
  }

  /** Request-phase blocking with a registered {@link JettyBlockResponseFunction}. */
  private final class JettyBrfFixture {
    final FakeRequest request = new FakeRequest("GET", null);
    final FakeResponse response = new FakeResponse();
    final JettyBlockResponseFunction brf =
        new JettyBlockResponseFunction(request.proxy, response.proxy);

    /** Records Jetty callback completions and {@code effectivelyBlocked()} calls, in order. */
    final List<String> events = new ArrayList<>();

    final TraceSegment segment =
        proxy(
            TraceSegment.class,
            (name, args) -> {
              events.add(name);
              return null;
            });
    Throwable callbackFailure;

    JettyBrfFixture() {
      response.completeWrites = false;
      RequestContext reqCtx = requestContext(brf, segment);
      AgentSpan span =
          proxy(
              AgentSpan.class,
              (name, args) -> {
                switch (name) {
                  case "getRequestBlockingAction":
                    return RBA;
                  case "getRequestContext":
                    return reqCtx;
                  default:
                    return null;
                }
              });
      request.attributes.put(
          DD_CONTEXT_ATTRIBUTE,
          proxy(Context.class, (name, args) -> "get".equals(name) ? span : null));
    }

    boolean tryBlock() {
      Callback callback =
          proxy(
              Callback.class,
              (name, args) -> {
                switch (name) {
                  case "getInvocationType":
                    return InvocationType.EITHER;
                  case "succeeded":
                    events.add(name);
                    return null;
                  case "failed":
                    events.add(name);
                    callbackFailure = (Throwable) args[0];
                    return null;
                  default:
                    return null;
                }
              });
      return JettyBlockResponseHelper.tryBlockRequest(request.proxy, callback);
    }
  }

  private boolean tryBlock(
      boolean hasContext,
      boolean hasSpan,
      boolean hasRba,
      boolean hasReqCtx,
      BlockResponseFunction brf) {
    Callback callback =
        proxy(
            Callback.class,
            (name, args) -> {
              if ("succeeded".equals(name)) {
                callbackSucceeded++;
              } else if ("failed".equals(name)) {
                callbackFailed++;
              }
              return null;
            });
    return JettyBlockResponseHelper.tryBlockRequest(
        request(hasContext, hasSpan, hasRba, hasReqCtx, brf), callback);
  }

  private Request request(
      boolean hasContext,
      boolean hasSpan,
      boolean hasRba,
      boolean hasReqCtx,
      BlockResponseFunction brf) {
    RequestContext reqCtx = requestContext(brf);
    AgentSpan span =
        proxy(
            AgentSpan.class,
            (name, args) -> {
              switch (name) {
                case "getRequestBlockingAction":
                  return hasRba ? RBA : null;
                case "getRequestContext":
                  return hasReqCtx ? reqCtx : null;
                default:
                  return null;
              }
            });
    Context context =
        proxy(Context.class, (name, args) -> "get".equals(name) && hasSpan ? span : null);
    return proxy(
        Request.class,
        (name, args) ->
            "getAttribute".equals(name) && DD_CONTEXT_ATTRIBUTE.equals(args[0]) && hasContext
                ? context
                : null);
  }

  private RequestContext requestContext(BlockResponseFunction brf) {
    return requestContext(brf, segment);
  }

  private RequestContext requestContext(BlockResponseFunction brf, TraceSegment traceSegment) {
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
            case "getBlockResponseFunction":
              return brf;
            case "getTraceSegment":
              return traceSegment;
            case "getData":
              return args[0] == RequestContextSlot.APPSEC ? appSecCtx : null;
            default:
              return null;
          }
        });
  }

  private BlockResponseFunction brf(String outcome) {
    switch (outcome) {
      case "false":
        return brf(false);
      case "throws":
        return throwingBrf();
      default:
        throw new IllegalArgumentException(outcome);
    }
  }

  private BlockResponseFunction brf(boolean result) {
    return (seg, statusCode, bct, extraHeaders, securityResponseId) -> {
      commits++;
      committedRba = new Flow.Action.RequestBlockingAction(statusCode, bct);
      return result;
    };
  }

  private BlockResponseFunction throwingBrf() {
    return (seg, statusCode, bct, extraHeaders, securityResponseId) -> {
      commits++;
      throw new IllegalStateException("boom");
    };
  }
}
