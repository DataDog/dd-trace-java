package datadog.trace.instrumentation.jetty12;

import org.eclipse.jetty.ee10.servlet.ServletChannelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Completes a Jetty ee10 async request that a {@code BlockingException} left suspended.
 *
 * <p>From Jetty 12.1, {@code ServletChannelState.onError} returns {@code false} for an ASYNC
 * request without an {@code AsyncListener} and does nothing else, so a blocking response committed
 * after {@code startAsync()} is never followed by a completion. On 12.0.16-12.0.x the same case
 * either aborts ({@code true}) or calls sendError, so the gate below never matches there.
 */
public final class JettyAsyncBlockCompletion {
  private static final Logger log = LoggerFactory.getLogger(JettyAsyncBlockCompletion.class);

  private JettyAsyncBlockCompletion() {}

  /**
   * Called when {@code onError} returned {@code false} (Jetty will not abort). Completes the
   * request only when every condition holds, otherwise leaves Jetty's behaviour untouched:
   *
   * <ul>
   *   <li>the failure carries a {@code BlockingException}: only AppSec blocking is handled;
   *   <li>no sendError is pending: Jetty already writes an error response;
   *   <li>the request is not aborted: nothing left to complete;
   *   <li>async was started: a non-async request completes on its own;
   *   <li>the response is committed: the blocking response was written, whereas completing an
   *       uncommitted one would send an empty 200.
   * </ul>
   *
   * <p>Never throws: {@code complete()} raises {@code IllegalStateException} in states such as
   * DISPATCH or EXPIRING, which are left to Jetty.
   */
  public static void completeSuspendedBlock(
      final ServletChannelState state, final Throwable failure) {
    try {
      if (JettyBlockingExceptions.findBlockingException(failure) == null
          || state.isSendError()
          || state.isAborted()
          || !state.isAsyncStarted()
          || !state.getServletChannel().isCommitted()) {
        return;
      }
      try {
        state.complete();
      } catch (IllegalStateException e) {
        log.debug("Unable to complete the async request suspended by a blocking response", e);
      }
    } catch (Exception e) {
      log.debug("Failed to complete the async request suspended by a blocking response", e);
    }
  }
}
