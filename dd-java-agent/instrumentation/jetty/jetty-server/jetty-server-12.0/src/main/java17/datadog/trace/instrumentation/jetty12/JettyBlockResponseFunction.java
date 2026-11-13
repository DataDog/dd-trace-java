package datadog.trace.instrumentation.jetty12;

import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.spanFrom;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * {@link BlockResponseFunction} for Jetty 12 core. Holds the per-request core request and response
 * (never the recycled {@code HttpChannelState}) and commits the blocking response asynchronously,
 * so a client that does not read cannot tie up the calling Jetty worker thread.
 *
 * <p>{@link #tryCommitBlockingResponse} returns {@code true} once Jetty accepted the write. {@link
 * TraceSegment#effectivelyBlocked()} or {@code block_failure} are resolved when the write
 * completes; a failure reported after the request ended may be lost. A failed write releases the
 * claim so later calls attempt the commit again.
 *
 * <p>The request phase shares the claim of this function through {@link
 * JettyBlockResponseHelper#tryBlockRequest}.
 */
public class JettyBlockResponseFunction implements BlockResponseFunction {
  private static final AtomicIntegerFieldUpdater<JettyBlockResponseFunction> CLAIMED =
      AtomicIntegerFieldUpdater.newUpdater(JettyBlockResponseFunction.class, "claimed");

  private final Request request;
  private final Response response;

  /** 1 while a blocking response write is owned by one evaluation and has not failed. */
  private volatile int claimed;

  public JettyBlockResponseFunction(Request request, Response response) {
    this.request = request;
    this.response = response;
  }

  @Override
  public boolean tryCommitBlockingResponse(
      TraceSegment segment,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    // resolved now: the context attribute is gone once the channel is recycled
    AgentSpan span = spanFrom(request);
    RequestContext reqCtx = span == null ? null : span.getRequestContext();
    // A single request can trigger several blocking evaluations (RASP, body, user blocking).
    // Only one of them writes; the others rely on the owner to resolve the outcome.
    if (!tryClaim()) {
      return true;
    }
    return JettyBlockResponseHelper.commitBlockingResponse(
        this, segment, reqCtx, statusCode, bct, extraHeaders, securityResponseId, Callback.NOOP);
  }

  Request getRequest() {
    return request;
  }

  Response getResponse() {
    return response;
  }

  @VisibleForTesting
  boolean isBlockingResponseInitiated() {
    return claimed != 0;
  }

  /**
   * Claims the right to write the blocking response.
   *
   * @return true if the caller now owns the blocking response write and must {@link #release()} it
   *     on failure; false if another evaluation already owns it
   */
  boolean tryClaim() {
    return CLAIMED.compareAndSet(this, 0, 1);
  }

  /** Releases the claim after a failed blocking response attempt. */
  void release() {
    claimed = 0;
  }
}
