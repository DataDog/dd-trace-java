package datadog.trace.instrumentation.tomcat7;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.MultipartContentDecoder;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

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
      // commit failed — response not sent, cannot block this request
      return false;
    }
    // Response was committed — mark as blocked on a best-effort basis.
    // effectivelyBlocked() can throw if the span is already finished; that must not suppress the
    // true return value since the response has already been sent to the client.
    try {
      reqCtx.getTraceSegment().effectivelyBlocked();
    } catch (Exception ignored) {
      // span already finished — response was sent, blocking succeeded
    }
    return true;
  }

  /**
   * Collects filenames and file contents from the given multipart parts, fires the AppSec IG
   * callbacks, and commits a blocking response if the WAF requests one.
   *
   * <p>Returns {@code true} if a blocking response was committed (the caller should replace the
   * parts collection with an empty list to prevent further processing).
   */
  public static boolean processPartsAndBlock(
      Collection<?> parts,
      RequestContext reqCtx,
      org.apache.catalina.Request catRequest,
      BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb,
      BiFunction<RequestContext, List<String>, Flow<Void>> contentCb) {
    // org.apache.catalina.Request.getRequest() returns the underlying ServletRequest.
    // TomcatServerInstrumentation is muzzled out in Payara (CoyoteAdapter.postParseRequest arg
    // types differ from standard Tomcat), so BlockResponseFunction is never registered there —
    // this Servlet API fallback is required to commit the blocking response in Payara.
    javax.servlet.ServletRequest sr = catRequest != null ? catRequest.getRequest() : null;
    HttpServletRequest fallbackReq =
        sr instanceof HttpServletRequest ? (HttpServletRequest) sr : null;
    HttpServletResponse fallbackResp = null;
    if (catRequest != null) {
      org.apache.catalina.Response cr = catRequest.getResponse();
      if (cr != null) {
        javax.servlet.ServletResponse svr = cr.getResponse();
        if (svr instanceof HttpServletResponse) {
          fallbackResp = (HttpServletResponse) svr;
        }
      }
    }
    List<String> filenames = null;
    List<String> contents = null;
    for (Object partObj : parts) {
      try {
        if (!(partObj instanceof Part)) {
          continue;
        }
        Part part = (Part) partObj;
        String filename = part.getSubmittedFileName();
        if (filename == null) {
          continue;
        }
        if (filenamesCb != null && !filename.isEmpty()) {
          if (filenames == null) {
            filenames = new ArrayList<>();
          }
          filenames.add(filename);
        }
        if (contentCb != null) {
          if (contents == null) {
            contents = new ArrayList<>();
          }
          if (contents.size() < MAX_FILE_CONTENT_COUNT) {
            try (InputStream is = part.getInputStream()) {
              contents.add(
                  MultipartContentDecoder.readInputStream(
                      is, MAX_FILE_CONTENT_BYTES, part.getContentType()));
            } catch (Exception ignored) {
              // stream read failed — report empty content rather than skipping the part entirely
              contents.add("");
            }
          }
        }
      } catch (Exception ignored) {
        // malformed or inaccessible part — skip and continue with remaining parts
      }
    }

    if (filenames != null && !filenames.isEmpty()) {
      Flow<Void> flow = filenamesCb.apply(reqCtx, filenames);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        if (tryBlock(
            reqCtx, fallbackReq, fallbackResp, (Flow.Action.RequestBlockingAction) action)) {
          return true;
        }
      }
    }

    if (contents != null && !contents.isEmpty()) {
      Flow<Void> contentFlow = contentCb.apply(reqCtx, contents);
      Flow.Action contentAction = contentFlow.getAction();
      if (contentAction instanceof Flow.Action.RequestBlockingAction) {
        return tryBlock(
            reqCtx, fallbackReq, fallbackResp, (Flow.Action.RequestBlockingAction) contentAction);
      }
    }

    return false;
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
