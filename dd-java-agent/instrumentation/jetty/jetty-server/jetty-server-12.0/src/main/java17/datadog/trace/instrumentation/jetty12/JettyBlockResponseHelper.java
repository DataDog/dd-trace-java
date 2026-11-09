package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and commits AppSec blocking responses on Jetty 12 core {@link Response} objects.
 *
 * <p>Only references Jetty 12 core and jetty-util types so it works for every environment (ee8,
 * ee9, ee10) sitting on top of the core server.
 */
public final class JettyBlockResponseHelper {
  private static final Logger log = LoggerFactory.getLogger(JettyBlockResponseHelper.class);

  /** Upper bound for waiting on the synchronous blocking response write. */
  static final long COMMIT_TIMEOUT_MILLIS = 5000;

  private static final ByteBuffer EMPTY_BODY = ByteBuffer.allocate(0).asReadOnlyBuffer();

  private JettyBlockResponseHelper() {}

  /**
   * Prepares the blocking response: marks the request with {@code DD_IGNORE_COMMIT_ATTRIBUTE},
   * resets the response and sets status, extra headers and, when a body is sent, Content-Type and
   * Content-Length.
   *
   * <p>The caller is expected to send the returned body with {@code response.write(true, body,
   * callback)}. No body is produced for {@link BlockingContentType#NONE} or HEAD requests.
   *
   * @return the body to write, or {@code null} if the response is already committed
   * @throws IllegalStateException if {@link Response#reset()} fails (e.g. committed concurrently)
   */
  public static ByteBuffer prepareBlockResponse(
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    if (response.isCommitted()) {
      return null;
    }
    request.setAttribute(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    response.reset();
    response.setStatus(BlockingActionHelper.getHttpCode(statusCode));
    HttpFields.Mutable headers = response.getHeaders();
    if (extraHeaders != null) {
      for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
        headers.put(h.getKey(), h.getValue());
      }
    }
    if (bct == BlockingContentType.NONE || HttpMethod.HEAD.is(request.getMethod())) {
      return EMPTY_BODY.duplicate();
    }
    String accept = request.getHeaders().get(HttpHeader.ACCEPT);
    TemplateType type = BlockingActionHelper.determineTemplateType(bct, accept);
    byte[] template = BlockingActionHelper.getTemplate(type, securityResponseId);
    headers.put(HttpHeader.CONTENT_TYPE, BlockingActionHelper.getContentType(type));
    headers.put(HttpHeader.CONTENT_LENGTH, template.length);
    return ByteBuffer.wrap(template);
  }

  /**
   * Prepares and writes the blocking response, waiting up to {@link #COMMIT_TIMEOUT_MILLIS} for the
   * write to complete. Calls {@link TraceSegment#effectivelyBlocked()} once the write is confirmed.
   *
   * @return true only if the blocking response was written successfully
   */
  public static boolean commitBlockingResponse(
      TraceSegment segment,
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    try {
      ByteBuffer body =
          prepareBlockResponse(
              request, response, statusCode, bct, extraHeaders, securityResponseId);
      if (body == null) {
        log.debug("Response already committed, cannot commit blocking response");
        return false;
      }
      // a completed CompletableFuture returns from get() without checking the interrupt flag
      CompletableFuture<Void> written = new CompletableFuture<>();
      response.write(true, body, Callback.from(written, NON_BLOCKING));
      written.get(COMMIT_TIMEOUT_MILLIS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while committing blocking response", e);
      return false;
    } catch (ExecutionException e) {
      log.debug("Failed to write blocking response", e.getCause());
      return false;
    } catch (Throwable t) {
      // TimeoutException, WritePendingException, reset() failing on a concurrent commit, ...
      log.debug("Failed to commit blocking response", t);
      return false;
    }
    try {
      segment.effectivelyBlocked();
    } catch (Throwable t) {
      // the blocking response was already written, blocking succeeded
      log.debug("Failed to mark segment as effectively blocked", t);
    }
    return true;
  }

  /**
   * Request-phase blocking: commits the blocking response decided while processing the request
   * (headers, URI, client IP) through the {@link BlockResponseFunction} registered on the request
   * context, and completes the Jetty {@code callback} when it succeeds.
   *
   * <p>Called on entry of {@code Server.handle(Request, Response, Callback)}. Fails open: when
   * there is nothing to block or the commit fails, it returns {@code false} and the handler chain
   * runs normally. A failed commit is reported as {@code block_failure} (by the {@code
   * (RequestContext, RequestBlockingAction)} overload, or here when the commit throws).
   *
   * @return true only if the blocking response was committed and the callback completed, in which
   *     case the caller must skip the handler chain
   */
  public static boolean tryBlockRequest(Request request, Callback callback) {
    Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (!(contextObj instanceof Context)) {
      return false;
    }
    AgentSpan span = AgentSpan.fromContext((Context) contextObj);
    if (span == null) {
      return false;
    }
    Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
    if (rba == null) {
      return false;
    }
    RequestContext reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return false;
    }
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf == null) {
      return false;
    }
    try {
      if (!brf.tryCommitBlockingResponse(reqCtx, rba)) {
        return false;
      }
    } catch (Throwable t) {
      log.debug("Failed to commit request blocking response", t);
      reportBlockFailure(reqCtx);
      return false;
    }
    try {
      callback.succeeded();
    } catch (Throwable t) {
      // the blocking response was already written, the handler chain must not run
      log.debug("Failed to complete callback after request blocking response", t);
    }
    return true;
  }

  private static void reportBlockFailure(RequestContext reqCtx) {
    try {
      Object appSecCtx = reqCtx.getData(RequestContextSlot.APPSEC);
      if (appSecCtx instanceof AppSecContext) {
        ((AppSecContext) appSecCtx).reportBlockFailure();
      }
    } catch (Throwable t) {
      log.debug("Failed to report block failure", t);
    }
  }
}
