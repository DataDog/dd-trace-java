package datadog.trace.instrumentation.jetty12;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import java.util.Map;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * {@link BlockResponseFunction} for Jetty 12 core. Holds the per-request core request and response
 * (never the recycled {@code HttpChannelState}) and commits the blocking response synchronously,
 * waiting a bounded time for the write.
 *
 * <p>If that wait times out or is interrupted, {@link #tryCommitBlockingResponse} returns {@code
 * true} while the write is still in flight: {@link TraceSegment#effectivelyBlocked()} or {@code
 * block_failure} are then resolved asynchronously when the write completes, and a late failure
 * clears the guard so later calls attempt the commit again.
 *
 * <p>The request phase does not use the synchronous commit: it writes asynchronously through {@link
 * JettyBlockResponseHelper#tryBlockRequest} and shares the guard of this function.
 */
public class JettyBlockResponseFunction implements BlockResponseFunction {
  private final Request request;
  private final Response response;

  /** Set while a blocking response write was initiated and has not failed. */
  private volatile boolean blockingResponseInitiated;

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
    // A single request can trigger several blocking evaluations (RASP, body, user blocking).
    // Once a block was committed, the response being committed is expected, not a failure.
    if (blockingResponseInitiated) {
      return true;
    }
    // set before the write so a late failure of a timed out write clears it in any order
    markBlockingResponseInitiated();
    if (JettyBlockResponseHelper.commitBlockingResponse(
        segment,
        request,
        response,
        statusCode,
        bct,
        extraHeaders,
        securityResponseId,
        this::clearBlockingResponseInitiated)) {
      return true;
    }
    clearBlockingResponseInitiated();
    return false;
  }

  Request getRequest() {
    return request;
  }

  Response getResponse() {
    return response;
  }

  boolean isBlockingResponseInitiated() {
    return blockingResponseInitiated;
  }

  void markBlockingResponseInitiated() {
    blockingResponseInitiated = true;
  }

  void clearBlockingResponseInitiated() {
    blockingResponseInitiated = false;
  }
}
