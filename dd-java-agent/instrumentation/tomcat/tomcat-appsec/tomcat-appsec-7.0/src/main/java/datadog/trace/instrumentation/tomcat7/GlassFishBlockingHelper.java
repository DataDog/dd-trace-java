package datadog.trace.instrumentation.tomcat7;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class GlassFishBlockingHelper {

  public static final int MAX_FILE_CONTENT_COUNT = Config.get().getAppSecMaxFileContentCount();
  public static final int MAX_FILE_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();

  /**
   * Attempts to commit a blocking response via the registered {@link BlockResponseFunction} or via
   * the Servlet API fallback, then marks the trace segment as effectively blocked.
   *
   * <p>Returns {@code true} if the response was committed (regardless of whether {@link
   * datadog.trace.api.internal.TraceSegment#effectivelyBlocked()} succeeded). Returns {@code false}
   * if no response could be committed.
   */
  public static boolean tryBlock(
      RequestContext reqCtx,
      HttpServletRequest fallbackReq,
      HttpServletResponse fallbackResp,
      Flow.Action.RequestBlockingAction rba) {
    try {
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
      } else if (!commitBlocking(fallbackReq, fallbackResp, rba)) {
        return false;
      }
    } catch (Exception ignored) {
      return false;
    }
    // Response was committed — mark as blocked on a best-effort basis.
    // effectivelyBlocked() can throw if the span is already finished; that must not suppress the
    // true return value since the response has already been sent to the client.
    try {
      reqCtx.getTraceSegment().effectivelyBlocked();
    } catch (Exception ignored) {
    }
    return true;
  }

  public static boolean commitBlocking(
      HttpServletRequest request,
      HttpServletResponse response,
      Flow.Action.RequestBlockingAction rba) {
    if (response == null) {
      return false;
    }
    try {
      if (response.isCommitted()) {
        return false;
      }
      response.reset();
      response.setStatus(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
      for (Map.Entry<String, String> e : rba.getExtraHeaders().entrySet()) {
        response.setHeader(e.getKey(), e.getValue());
      }
      if (rba.getBlockingContentType() != BlockingContentType.NONE) {
        String accept = request != null ? request.getHeader("Accept") : null;
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), accept);
        byte[] body = BlockingActionHelper.getTemplate(type, rba.getSecurityResponseId());
        if (body != null) {
          response.setHeader("Content-Type", BlockingActionHelper.getContentType(type));
          response.setHeader("Content-Length", Integer.toString(body.length));
          response.getOutputStream().write(body);
        }
      }
      response.flushBuffer();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
