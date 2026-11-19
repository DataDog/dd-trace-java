package datadog.trace.instrumentation.jetty12;

import static datadog.trace.instrumentation.jetty12.JettyBlockResponseHelper.spanFrom;

import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.eclipse.jetty.ee10.servlet.ServletChannelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Completes a Jetty ee10 async request that a {@code BlockingException} left suspended.
 *
 * <p>From Jetty 12.1, {@code ServletChannelState.onError} returns {@code false} for an ASYNC
 * request without an {@code AsyncListener} and does nothing else: no sendError, no abort, no
 * complete. When a {@code BlockingException} is thrown after {@code startAsync()} (e.g. {@code
 * ProxyServlet} with RASP SSRF), the blocking response is committed but the request stays suspended
 * forever: the connection hangs and the server span is never finished. On 12.0.16-12.0.x the same
 * case either aborts ({@code true}) or calls sendError, so the gate below never matches there.
 */
public final class JettyAsyncBlockCompletion {
  private static final Logger log = LoggerFactory.getLogger(JettyAsyncBlockCompletion.class);

  private JettyAsyncBlockCompletion() {}

  /**
   * Called when {@code onError} returned {@code false} (Jetty will not abort). Completes the
   * request only for a {@code BlockingException} whose blocking response AppSec initiated and
   * committed, otherwise leaves Jetty's behaviour untouched. Only a response written by AppSec is
   * completed, so an app-owned committed response (e.g. streamed by an async worker) is left to the
   * app: completing hands the channel back to the connection. A pending sendError is also left to
   * Jetty, which then writes the error response itself.
   *
   * <p>The checks are not atomic with {@code complete()}: a concurrent {@code dispatch()} or {@code
   * complete()} by the app is tolerated, since {@code complete()} is a no-op once COMPLETE and its
   * {@code IllegalStateException} is caught.
   *
   * <p>Never throws.
   */
  public static void completeSuspendedBlock(
      final ServletChannelState state, final Throwable failure) {
    try {
      if (JettyBlockingExceptions.findBlockingException(failure) == null
          || state.isSendError()
          || state.isAborted()
          || !state.isAsyncStarted()
          || !state.getServletChannel().isCommitted()
          || !isBlockingResponseInitiated(state)) {
        return;
      }
      state.complete();
    } catch (Exception e) {
      // complete() throws IllegalStateException in states other than ASYNC/COMPLETE*
      log.debug("Unable to complete the async request suspended by a blocking response", e);
    }
  }

  /** Returns whether AppSec initiated the blocking response of this request. */
  private static boolean isBlockingResponseInitiated(final ServletChannelState state) {
    AgentSpan span = spanFrom(state.getServletChannel().getRequest());
    RequestContext reqCtx = span == null ? null : span.getRequestContext();
    BlockResponseFunction brf = reqCtx == null ? null : reqCtx.getBlockResponseFunction();
    return brf instanceof JettyBlockResponseFunction
        && ((JettyBlockResponseFunction) brf).isBlockingResponseInitiated();
  }
}
