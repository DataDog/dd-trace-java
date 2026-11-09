package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.lang.reflect.Proxy;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Unit tests for {@link JettyBlockResponseHelper#tryBlockRequest}.
 *
 * <p>Uses dynamic proxy fakes so the tests compile against every Jetty 12 version.
 */
class JettyRequestBlockingTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final TraceSegment segment = proxy(TraceSegment.class, (name, args) -> null);
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

  @Test
  void failsOpenAndReportsWhenCommitFails() {
    boolean blocked = tryBlock(true, true, true, true, brf(false));

    assertFalse(blocked);
    assertEquals(1, commits);
    assertEquals(0, callbackSucceeded);
    assertEquals(0, callbackFailed);
    assertEquals(1, blockFailures, "the (RequestContext, RBA) overload reports block_failure");
  }

  @Test
  void failsOpenAndReportsWhenCommitThrows() {
    BlockResponseFunction throwing =
        (seg, status, bct, headers, rid) -> {
          commits++;
          throw new IllegalStateException("boom");
        };

    boolean blocked = tryBlock(true, true, true, true, throwing);

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

  @Test
  void commitReturnsFalseAndReportsWhenBrfFails() {
    assertFalse(
        JettyBlockResponseHelper.tryCommitBlockingResponse(requestContext(brf(false)), RBA));

    assertEquals(1, commits);
    assertEquals(1, blockFailures, "the (RequestContext, RBA) overload reports block_failure");
  }

  @Test
  void commitReturnsFalseAndReportsWhenBrfThrows() {
    BlockResponseFunction throwing =
        (seg, status, bct, headers, rid) -> {
          commits++;
          throw new IllegalStateException("boom");
        };

    assertFalse(JettyBlockResponseHelper.tryCommitBlockingResponse(requestContext(throwing), RBA));

    assertEquals(1, commits);
    assertEquals(1, blockFailures);
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
    AppSecContext appSecCtx =
        proxy(
            AppSecContext.class,
            (name, args) -> {
              if ("reportBlockFailure".equals(name)) {
                blockFailures++;
              }
              return null;
            });
    RequestContext reqCtx =
        proxy(
            RequestContext.class,
            (name, args) -> {
              switch (name) {
                case "getBlockResponseFunction":
                  return brf;
                case "getTraceSegment":
                  return segment;
                case "getData":
                  return args[0] == RequestContextSlot.APPSEC ? appSecCtx : null;
                default:
                  return null;
              }
            });
    return reqCtx;
  }

  private BlockResponseFunction brf(boolean result) {
    return (seg, statusCode, bct, extraHeaders, securityResponseId) -> {
      commits++;
      committedRba = new Flow.Action.RequestBlockingAction(statusCode, bct);
      return result;
    };
  }

  private interface Handler {
    Object handle(String methodName, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            JettyRequestBlockingTest.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "toString":
                  return type.getSimpleName() + "Fake";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                default:
                  return handler.handle(method.getName(), args);
              }
            });
  }
}
