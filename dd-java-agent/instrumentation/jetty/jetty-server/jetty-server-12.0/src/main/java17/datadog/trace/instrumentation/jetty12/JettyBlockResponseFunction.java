package datadog.trace.instrumentation.jetty12;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import java.util.Map;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * {@link BlockResponseFunction} for Jetty 12 core. Holds the per-request core request and response
 * (never the recycled {@code HttpChannelState}) and commits the blocking response synchronously.
 */
public class JettyBlockResponseFunction implements BlockResponseFunction {
  private final Request request;
  private final Response response;
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
    boolean committed =
        JettyBlockResponseHelper.commitBlockingResponse(
            segment, request, response, statusCode, bct, extraHeaders, securityResponseId);
    if (committed) {
      blockingResponseInitiated = true;
    }
    return committed;
  }
}
