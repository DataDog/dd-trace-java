package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.markEffectivelyBlocked;
import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.prepareBlockResponse;
import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.reportBlockFailure;
import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.spanFrom;
import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty12.JettyDecorator.effectiveStatus;

import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Response-phase AppSec blocking for Jetty 12: runs the response IG callbacks (status and response
 * headers) right before the first write commits the response, and replaces the response with the
 * blocking one when they ask for it.
 *
 * <p>Never waits for the blocking write: {@code ChannelResponse.write} can run on non-blocking
 * paths (async servlet IO), so the outcome is handled in {@link BlockingCallback}.
 */
public final class JettyCommitResponseHelper {
  private static final Logger log = LoggerFactory.getLogger(JettyCommitResponseHelper.class);

  private JettyCommitResponseHelper() {}

  /**
   * Called on entry of {@code ChannelResponse.write(boolean, ByteBuffer, Callback)}.
   *
   * @return true when the blocking response was written instead, in which case the caller must skip
   *     the original write (its {@code callback} is completed by the blocking write)
   */
  public static boolean tryBlockOnCommit(Response response, Callback callback) {
    return tryBlockOnCommit(DECORATE, response, callback);
  }

  static boolean tryBlockOnCommit(JettyDecorator decorator, Response response, Callback callback) {
    if (response.isCommitted()) {
      return false;
    }
    Request request = response.getRequest();
    if (request == null || request.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
      return false;
    }
    AgentSpan span = spanFrom(request);
    if (span == null) {
      return false;
    }
    RequestContext reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return false;
    }
    int status = effectiveStatus(response);
    if (HttpStatus.isInformational(status)) {
      // interim responses do not commit the final response
      return false;
    }

    // set before the IG callbacks: one evaluation per request, and skips the re-entrant write
    request.setAttribute(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    Flow<Void> flow =
        decorator.callIGCallbackResponseAndHeaders(
            span, response, status, ExtractAdapter.Response.GETTER);
    Flow.Action action = flow.getAction();
    if (!(action instanceof Flow.Action.RequestBlockingAction)) {
      return false;
    }
    Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;

    try {
      ByteBuffer body =
          prepareBlockResponse(
              request,
              response,
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders(),
              rba.getSecurityResponseId());
      if (body == null) {
        log.debug("Response committed concurrently, cannot commit blocking response");
        reportBlockFailure(reqCtx);
        return false;
      }
      response.write(true, body, new BlockingCallback(reqCtx, callback));
    } catch (Throwable t) {
      // fail open: the original write proceeds
      log.debug("Failed to commit blocking response on response commit", t);
      reportBlockFailure(reqCtx);
      return false;
    }
    // separate from the commit: the blocking write is in flight, the original write must be skipped
    try {
      claimBrf(reqCtx);
    } catch (Throwable t) {
      log.debug("Failed to mark the block response function as blocked", t);
    }
    return true;
  }

  /**
   * Once the blocking write is handed to Jetty the response is committed by us: later blocking
   * evaluations on the same request (RASP, user blocking) go through the BRF, which must treat that
   * commit as expected instead of reporting a {@code block_failure}. Claimed when the write is
   * started rather than on its success so an async write still in flight is covered too. The claim
   * is never released here: a failed write is reported once by {@link BlockingCallback#failed}, and
   * a later BRF attempt on the committed response would only report it again. The segment is still
   * marked only on success.
   */
  private static void claimBrf(RequestContext reqCtx) {
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf instanceof JettyBlockResponseFunction) {
      // the block page is already being written: the claim only records that this request is
      // blocked, so a failed claim (another evaluation already blocking) needs no handling
      ((JettyBlockResponseFunction) brf).tryClaim();
    }
  }

  /**
   * Completes the original write callback once the blocking response write completes, marking the
   * segment as effectively blocked on success and reporting {@code block_failure} on failure.
   */
  static final class BlockingCallback implements Callback {
    private final RequestContext reqCtx;
    private final TraceSegment segment;
    private final Callback delegate;

    BlockingCallback(RequestContext reqCtx, Callback delegate) {
      this.reqCtx = reqCtx;
      this.segment = reqCtx.getTraceSegment();
      this.delegate = delegate != null ? delegate : Callback.NOOP;
    }

    @Override
    public void succeeded() {
      markEffectivelyBlocked(segment);
      delegate.succeeded();
    }

    @Override
    public void failed(Throwable x) {
      log.debug("Failed to write blocking response on response commit", x);
      reportBlockFailure(reqCtx);
      delegate.failed(x);
    }

    @Override
    public InvocationType getInvocationType() {
      return delegate.getInvocationType();
    }
  }
}
