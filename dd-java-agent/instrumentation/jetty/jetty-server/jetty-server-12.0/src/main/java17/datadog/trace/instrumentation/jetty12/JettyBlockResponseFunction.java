package datadog.trace.instrumentation.jetty12;

import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.spanFrom;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;

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
 *
 * <p>When the Handler callback was wrapped by {@link #deferCompletion}, the blocking response write
 * is tracked as in flight so that completing the request does not cut it.
 */
public class JettyBlockResponseFunction implements BlockResponseFunction {
  private static final AtomicIntegerFieldUpdater<JettyBlockResponseFunction> CLAIMED =
      AtomicIntegerFieldUpdater.newUpdater(JettyBlockResponseFunction.class, "claimed");

  private final Request request;
  private final Response response;

  /** 1 while a blocking response write is owned by one evaluation and has not failed. */
  private volatile int claimed;

  /** Wrapper of the Handler callback, set once on the handler chain path; null if not installed. */
  private volatile JettyDeferredCompletion deferredCompletion;

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

  /**
   * Wraps the Handler callback so its completion waits for an in-flight blocking response write.
   * Installed at most once per request: later calls return {@code handlerCallback} unchanged.
   *
   * @return the callback to pass to the handler chain
   */
  Callback deferCompletion(Callback handlerCallback) {
    if (deferredCompletion != null) {
      return handlerCallback;
    }
    JettyDeferredCompletion wrapper = new JettyDeferredCompletion(handlerCallback);
    deferredCompletion = wrapper;
    return wrapper;
  }

  /**
   * Marks a blocking response write as in flight on the Handler callback wrapper. Must be called
   * before the write is issued: Jetty may run the write callback inside the write call. A no-op
   * when no wrapper is installed. Reading the wrapper on each call is safe: it is installed on
   * {@code Server.handle} entry, before any write that can outlive the handler chain starts.
   */
  void blockWriteStarted() {
    JettyDeferredCompletion deferred = deferredCompletion;
    if (deferred != null) {
      deferred.blockWriteStarted();
    }
  }

  /**
   * Marks the blocking response write as finished, replaying a request completion parked while it
   * was in flight. A no-op when no wrapper is installed. Can run on Jetty write callback threads:
   * never throws.
   */
  void blockWriteFinished() {
    JettyDeferredCompletion deferred = deferredCompletion;
    if (deferred != null) {
      deferred.blockWriteFinished();
    }
  }

  /**
   * Finishes the failed blocking response write, then releases the claim. The order matters: after
   * the release a retry may start a new write, which a late finish would wrongly clear.
   */
  void releaseAfterFailedWrite() {
    blockWriteFinished();
    release();
  }

  /**
   * Returns the invocation type for a blocking response write callback whose own completion is
   * {@code base}. When a wrapper is installed the write callback may replay the Handler completion,
   * so its type is combined with the wrapper's.
   */
  InvocationType writeCallbackType(InvocationType base) {
    JettyDeferredCompletion deferred = deferredCompletion;
    return deferred == null ? base : Invocable.combine(base, deferred.getInvocationType());
  }

  /** Returns whether a blocking response write is claimed by one evaluation and has not failed. */
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
