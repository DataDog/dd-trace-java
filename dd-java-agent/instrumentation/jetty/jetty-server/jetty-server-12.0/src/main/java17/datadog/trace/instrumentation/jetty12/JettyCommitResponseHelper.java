package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.logWriteFailure;
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

    JettyBlockResponseFunction claimed = null;
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
      // claim before writing: Jetty can complete the write, and run the original callback, inside
      // write() itself
      claimed = claimBrf(reqCtx);
      BlockingCallback blockingCallback = new BlockingCallback(reqCtx, callback, claimed);
      if (claimed != null) {
        // before the write: Jetty may run the write callback inside write()
        claimed.blockWriteStarted();
      }
      response.write(true, body, blockingCallback);
    } catch (Throwable t) {
      // fail open: nothing was accepted, the original write proceeds
      log.debug("Failed to commit blocking response on response commit", t);
      if (claimed != null) {
        claimed.releaseAfterFailedWrite();
      }
      reportBlockFailure(reqCtx);
      return false;
    }
    return true;
  }

  /**
   * Records on the request's BRF that this request is blocked by the response-phase hook, so later
   * blocking evaluations on the same request (RASP, user blocking) treat the committed response as
   * expected instead of reporting a {@code block_failure}. Taken before the blocking write is
   * handed to Jetty, because Jetty can complete it inside {@code write()}. It is released only when
   * nothing was accepted (the write throws synchronously). It is kept when an accepted write later
   * fails: that failure is reported once by {@link BlockingCallback#failed}, a retry on the
   * committed response cannot succeed, and keeping it lets callers still abort with a {@code
   * BlockingException}.
   *
   * @return the BRF if this call took the claim and must release it on a synchronous failure, or
   *     {@code null} if there is no Jetty BRF or another evaluation already owns the block
   */
  private static JettyBlockResponseFunction claimBrf(RequestContext reqCtx) {
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf instanceof JettyBlockResponseFunction) {
      JettyBlockResponseFunction jettyBrf = (JettyBlockResponseFunction) brf;
      if (jettyBrf.tryClaim()) {
        return jettyBrf;
      }
    }
    return null;
  }

  /**
   * Completes the original write callback once the blocking response write completes, marking the
   * segment as effectively blocked on success and reporting {@code block_failure} on failure. Both
   * outcomes then finish the in-flight write on the Handler callback wrapper, if any, replaying a
   * request completion parked while the write was pending: the write callback runs before the
   * request completes, as in Jetty without the wrapper.
   */
  static final class BlockingCallback implements Callback {
    private final RequestContext reqCtx;
    private final TraceSegment segment;
    private final Callback delegate;

    /** BRF claimed by this write, tracking it as in flight; null when the claim was not taken. */
    private final JettyBlockResponseFunction claimed;

    BlockingCallback(RequestContext reqCtx, Callback delegate, JettyBlockResponseFunction claimed) {
      this.reqCtx = reqCtx;
      this.segment = reqCtx.getTraceSegment();
      this.delegate = delegate != null ? delegate : Callback.NOOP;
      this.claimed = claimed;
    }

    @Override
    public void succeeded() {
      // recorded before a parked completion is replayed and the span can finish
      markEffectivelyBlocked(segment);
      try {
        delegate.succeeded();
      } finally {
        blockWriteFinished();
      }
    }

    @Override
    public void failed(Throwable x) {
      logWriteFailure("Failed to write blocking response on response commit", x);
      reportBlockFailure(reqCtx);
      try {
        delegate.failed(x);
      } finally {
        // the claim is kept (see claimBrf), so no retry can start a new write
        blockWriteFinished();
      }
    }

    /** Runs on Jetty write callback threads; never throws. */
    private void blockWriteFinished() {
      if (claimed != null) {
        claimed.blockWriteFinished();
      }
    }

    @Override
    public InvocationType getInvocationType() {
      InvocationType base = delegate.getInvocationType();
      return claimed == null ? base : claimed.writeCallbackType(base);
    }
  }
}
