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
import java.util.concurrent.TimeoutException;
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
  static ByteBuffer prepareBlockResponse(
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    if (response.isCommitted()) {
      return null;
    }
    // build the body before reset() so a failure here does not leave a half-reset response
    byte[] template = null;
    String contentType = null;
    if (bct != BlockingContentType.NONE && !HttpMethod.HEAD.is(request.getMethod())) {
      String accept = request.getHeaders().get(HttpHeader.ACCEPT);
      TemplateType type = BlockingActionHelper.determineTemplateType(bct, accept);
      template = BlockingActionHelper.getTemplate(type, securityResponseId);
      contentType = BlockingActionHelper.getContentType(type);
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
    if (template == null) {
      return EMPTY_BODY.duplicate();
    }
    headers.put(HttpHeader.CONTENT_TYPE, contentType);
    headers.put(HttpHeader.CONTENT_LENGTH, template.length);
    return ByteBuffer.wrap(template);
  }

  /**
   * Prepares and writes the blocking response, waiting up to {@link #COMMIT_TIMEOUT_MILLIS} for the
   * write to complete. Calls {@link TraceSegment#effectivelyBlocked()} once the write is confirmed.
   *
   * <p>If the wait times out or is interrupted the write is still in flight: the blocking response
   * is considered initiated, and the segment is marked when the write completes.
   *
   * @return true if the blocking response was written, or its write is still in flight
   */
  static boolean commitBlockingResponse(
      TraceSegment segment,
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    return commitBlockingResponse(
        segment,
        request,
        response,
        statusCode,
        bct,
        extraHeaders,
        securityResponseId,
        COMMIT_TIMEOUT_MILLIS);
  }

  /** Variant with an explicit wait bound, used by tests. */
  static boolean commitBlockingResponse(
      TraceSegment segment,
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId,
      long timeoutMillis) {
    // a completed CompletableFuture returns from get() without checking the interrupt flag
    CompletableFuture<Void> written = new CompletableFuture<>();
    try {
      ByteBuffer body =
          prepareBlockResponse(
              request, response, statusCode, bct, extraHeaders, securityResponseId);
      if (body == null) {
        log.debug("Response already committed, cannot commit blocking response");
        return false;
      }
      response.write(true, body, Callback.from(written, NON_BLOCKING));
      written.get(timeoutMillis, MILLISECONDS);
    } catch (TimeoutException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.debug("Blocking response write still pending, completing it asynchronously", e);
      completeAsync(written, segment, request);
      return true;
    } catch (Throwable t) {
      // write failure, WritePendingException, reset() failing on a concurrent commit, ...
      log.debug(
          "Failed to commit blocking response", t instanceof ExecutionException ? t.getCause() : t);
      return false;
    }
    markEffectivelyBlocked(segment);
    return true;
  }

  /**
   * Marks the segment when a pending write succeeds and reports {@code block_failure} when it
   * fails. The request context is resolved now: the context attribute is gone once the channel is
   * recycled.
   */
  private static void completeAsync(
      CompletableFuture<Void> written, TraceSegment segment, Request request) {
    AgentSpan span = spanFrom(request);
    RequestContext reqCtx = span == null ? null : span.getRequestContext();
    written.whenComplete((v, t) -> {
      if (t == null) {
        markEffectivelyBlocked(segment);
      } else {
        log.debug("Pending blocking response write failed", t);
        if (reqCtx != null) {
          reportBlockFailure(reqCtx);
        }
      }
    });
  }

  /**
   * Request-phase blocking: commits the blocking response decided while processing the request
   * (headers, URI, client IP) and completes the Jetty {@code callback}. Fails open: on {@code
   * false} the handler chain runs normally.
   *
   * @return true only if the blocking response was committed, in which case the caller must skip
   *     the handler chain
   */
  public static boolean tryBlockRequest(Request request, Callback callback) {
    AgentSpan span = spanFrom(request);
    if (span == null) {
      return false;
    }
    Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
    if (rba == null) {
      return false;
    }
    if (!tryCommitBlockingResponse(span.getRequestContext(), rba)) {
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

  /**
   * Commits a blocking response through the {@link BlockResponseFunction} registered on the request
   * context.
   *
   * <p>Returns {@code false} without reporting when there is no request context or no {@link
   * BlockResponseFunction} (nothing was attempted). A {@code false} commit result is reported as
   * {@code block_failure} by the {@code (RequestContext, RequestBlockingAction)} overload; a commit
   * that throws is reported here.
   *
   * @return true only if the blocking response was committed
   */
  public static boolean tryCommitBlockingResponse(
      RequestContext reqCtx, Flow.Action.RequestBlockingAction rba) {
    if (reqCtx == null || rba == null) {
      return false;
    }
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf == null) {
      return false;
    }
    try {
      return brf.tryCommitBlockingResponse(reqCtx, rba);
    } catch (Throwable t) {
      log.debug("Failed to commit blocking response", t);
      reportBlockFailure(reqCtx);
      return false;
    }
  }

  /** Returns the server span stored in the request context attribute, or {@code null}. */
  static AgentSpan spanFrom(Request request) {
    Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    return contextObj instanceof Context ? AgentSpan.fromContext((Context) contextObj) : null;
  }

  static void markEffectivelyBlocked(TraceSegment segment) {
    try {
      segment.effectivelyBlocked();
    } catch (Throwable t) {
      // the blocking response was already written, blocking succeeded
      log.debug("Failed to mark segment as effectively blocked", t);
    }
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
