package datadog.trace.instrumentation.jetty12;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.determineTemplateType;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.getContentType;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.getHttpCode;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.getTemplate;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and commits AppSec blocking responses on Jetty 12 core {@link Response} objects.
 *
 * <p>Only references Jetty 12 core and jetty-util types so it works for every environment (ee8,
 * ee9, ee10) sitting on top of the core server.
 *
 * <p>Blocking response writes never wait: their outcome is resolved in a Jetty write callback. A
 * {@code block_failure} reported there after the request ended may be lost, a known gap shared with
 * other async frameworks.
 */
public final class JettyBlockResponseHelper {
  private static final Logger log = LoggerFactory.getLogger(JettyBlockResponseHelper.class);

  private static final ByteBuffer EMPTY_BODY = ByteBuffer.allocate(0).asReadOnlyBuffer();

  private JettyBlockResponseHelper() {}

  /**
   * Prepares the blocking response: resets the response, marks the request with {@code
   * DD_IGNORE_COMMIT_ATTRIBUTE} and sets status, extra headers and, when a body is sent,
   * Content-Type and Content-Length.
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
      TemplateType type = determineTemplateType(bct, accept);
      template = getTemplate(type, securityResponseId);
      contentType = getContentType(type);
    }
    response.reset();
    // reset() does not write, so setting the attribute after it cannot miss a re-entrant write
    request.setAttribute(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    response.setStatus(getHttpCode(statusCode));
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
   * Prepares the blocking response and hands it to Jetty without waiting for the write. The caller
   * must hold the claim of {@code brf}.
   *
   * <p>The write callback marks {@code segment} on success and then completes {@code onWritten}; on
   * failure it reports {@code block_failure} through {@code reqCtx} (when not null), releases the
   * claim and fails {@code onWritten}. {@code onWritten} also sets the invocation type of the write
   * callback.
   *
   * <p>When nothing could be sent the claim is released (and a prepared response restored) and
   * false is returned without reporting: the caller decides how the failure is reported.
   *
   * <p>When the Handler callback was wrapped ({@link JettyBlockResponseFunction#deferCompletion}),
   * the write is marked in flight before it is issued and finished on every exit, always before the
   * claim is released: after the release a retry may start a new write.
   *
   * @return true once Jetty accepted the write; false if nothing was sent
   */
  static boolean commitBlockingResponse(
      JettyBlockResponseFunction brf,
      TraceSegment segment,
      RequestContext reqCtx,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId,
      Callback onWritten) {
    Request request = brf.getRequest();
    Response response = brf.getResponse();
    ByteBuffer body;
    try {
      body =
          prepareBlockResponse(
              request, response, statusCode, bct, extraHeaders, securityResponseId);
    } catch (Exception e) {
      // template failure or reset() failing on a concurrent commit: the response was not prepared
      log.debug("Failed to prepare blocking response", e);
      brf.release();
      return false;
    }
    if (body == null) {
      log.debug("Response already committed, cannot commit blocking response");
      brf.release();
      return false;
    }
    Callback writeCallback =
        Callback.from(
            brf.writeCallbackType(Invocable.getInvocationType(onWritten)),
            () -> {
              // recorded before a parked completion is replayed and the span can finish
              markEffectivelyBlocked(segment);
              brf.blockWriteFinished();
              onWritten.succeeded();
            },
            failure -> {
              logWriteFailure("Blocking response write failed", failure);
              if (reqCtx != null) {
                reportBlockFailure(reqCtx);
              }
              brf.releaseAfterFailedWrite();
              onWritten.failed(failure);
            });
    // before the write: Jetty may run the write callback inside write()
    brf.blockWriteStarted();
    try {
      response.write(true, body, writeCallback);
    } catch (Exception e) {
      // Jetty reports write failures through the callback; a throw (e.g. WritePendingException)
      // means nothing was sent
      log.debug("Failed to write blocking response", e);
      restoreAfterFailedBlock(request, response);
      brf.releaseAfterFailedWrite();
      return false;
    }
    return true;
  }

  /**
   * Undoes {@link #prepareBlockResponse} before failing open, so the application does not run on a
   * response carrying the blocking status and headers. The reset also clears headers set before
   * (e.g. by customizers): an accepted trade-off versus leaking the blocking page headers.
   */
  static void restoreAfterFailedBlock(Request request, Response response) {
    try {
      if (!response.isCommitted()) {
        response.reset();
      }
    } catch (Exception e) {
      log.debug("Failed to reset response after failed blocking attempt", e);
    }
    try {
      request.removeAttribute(DD_IGNORE_COMMIT_ATTRIBUTE);
    } catch (Exception e) {
      log.debug("Failed to remove ignore commit attribute after failed blocking attempt", e);
    }
  }

  /**
   * Request-phase blocking: commits the blocking response decided while processing the request
   * (headers, URI, client IP) and completes the Jetty {@code callback}. Fails open: on {@code
   * false} the handler chain runs normally.
   *
   * <p>With a {@link JettyBlockResponseFunction} the response is written asynchronously and {@code
   * callback} is completed from the write callback: completing it while the write is pending would
   * fail the channel with a "write pending" error.
   *
   * @return true if the blocking response was committed or handed to Jetty for writing, in which
   *     case the caller must skip the handler chain
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
    RequestContext reqCtx = span.getRequestContext();
    BlockResponseFunction brf = reqCtx == null ? null : reqCtx.getBlockResponseFunction();
    if (brf instanceof JettyBlockResponseFunction
        && ((JettyBlockResponseFunction) brf).tryClaim()) {
      if (commitBlockingResponse(
          (JettyBlockResponseFunction) brf,
          reqCtx.getTraceSegment(),
          reqCtx,
          rba.getStatusCode(),
          rba.getBlockingContentType(),
          rba.getExtraHeaders(),
          rba.getSecurityResponseId(),
          callback)) {
        return true;
      }
      reportBlockFailure(reqCtx);
      return false;
    }
    if (!tryCommitBlockingResponse(reqCtx, rba)) {
      return false;
    }
    try {
      callback.succeeded();
    } catch (Exception e) {
      // the blocking response was already written, the handler chain must not run
      log.debug("Failed to complete callback after request blocking response", e);
    }
    return true;
  }

  /**
   * Wraps the Jetty Handler {@code callback} with a {@link JettyDeferredCompletion} so that a
   * mid-handler blocking response write is not cut when the request completes. Must only be called
   * once the handler chain is going to run (not when {@link #tryBlockRequest} blocked).
   *
   * @return the wrapper when AppSec is active and a {@link JettyBlockResponseFunction} is
   *     registered for {@code request}; {@code callback} otherwise, including on any failure
   */
  public static Callback deferCompletion(Request request, Callback callback) {
    if (!APPSEC_ACTIVE) {
      return callback;
    }
    try {
      AgentSpan span = spanFrom(request);
      RequestContext reqCtx = span == null ? null : span.getRequestContext();
      BlockResponseFunction brf = reqCtx == null ? null : reqCtx.getBlockResponseFunction();
      if (brf instanceof JettyBlockResponseFunction) {
        return ((JettyBlockResponseFunction) brf).deferCompletion(callback);
      }
    } catch (Exception e) {
      log.debug("Failed to defer the request completion", e);
    }
    return callback;
  }

  /**
   * Fails the Handler callback wrapper with a {@code Throwable} that escaped {@code Server.handle}
   * while the wrapper is deferring (a blocking response write is in flight).
   *
   * <p>Jetty's {@code HandlerInvoker} fails its own reference to the Handler callback with such a
   * throwable, bypassing the wrapper installed by {@link #deferCompletion(Request, Callback)},
   * which would cut the in-flight write. Servlet environments catch {@code BlockingException}
   * themselves; this covers core handlers that let it escape, including after completing the
   * wrapper: that first completion stays parked, {@code failure} is dropped as a second completion,
   * and the parked outcome is replayed when the write finishes. When the wrapper is not deferring
   * Jetty keeps handling the throwable as it would without the agent.
   *
   * <p>Never throws an {@code Exception}; an {@code Error} is left to the advice's suppress
   * handler.
   *
   * @param callback the callback the handler chain received
   * @return true when {@code callback} is the wrapper and it was deferring, in which case the
   *     caller must swallow {@code failure} and report the request as handled; false otherwise,
   *     including on any failure, in which case {@code failure} must propagate unchanged
   */
  public static boolean failDeferred(Callback callback, Throwable failure) {
    if (!(callback instanceof JettyDeferredCompletion)) {
      return false;
    }
    JettyDeferredCompletion deferred = (JettyDeferredCompletion) callback;
    if (!deferred.isDeferring()) {
      return false;
    }
    try {
      // if the write finishes meanwhile the wrapper forwards the failure directly
      deferred.failed(failure);
      return true;
    } catch (Exception e) {
      log.debug("Failed to fail the deferred request completion", e);
      return false;
    }
  }

  /**
   * Completes the Handler callback wrapper when {@code Server.handle} returned {@code false} after
   * a blocking response was initiated on the handler chain.
   *
   * <p>On {@code false} Jetty's {@code HandlerInvoker} writes a 404 error with its own reference to
   * the Handler callback, bypassing the wrapper installed by {@link #deferCompletion(Request,
   * Callback)}: it would write over the blocking response or complete the request while the block
   * write is in flight. Since {@code false} means the handler will not complete the callback, the
   * caller may only report the request as handled once the wrapper was completed here: it is
   * completed with {@code succeeded()}, parked while the block write is in flight and replayed when
   * it finishes.
   *
   * <p>Never throws an {@code Exception}; an {@code Error} is left to the advice's suppress
   * handler.
   *
   * @param request the request whose context holds the blocking response function
   * @param callback the callback the handler chain received
   * @return true when {@code callback} is the wrapper, a blocking response was initiated and the
   *     wrapper was completed, in which case the caller must report the request as handled; false
   *     otherwise, including on any failure, in which case Jetty handles {@code false} unchanged
   */
  public static boolean completeUnhandledBlock(Request request, Callback callback) {
    if (!(callback instanceof JettyDeferredCompletion)) {
      return false;
    }
    try {
      AgentSpan span = spanFrom(request);
      RequestContext reqCtx = span == null ? null : span.getRequestContext();
      BlockResponseFunction brf = reqCtx == null ? null : reqCtx.getBlockResponseFunction();
      if (!(brf instanceof JettyBlockResponseFunction)
          || !((JettyBlockResponseFunction) brf).isBlockingResponseInitiated()) {
        return false;
      }
      callback.succeeded();
      return true;
    } catch (Exception e) {
      log.debug("Failed to complete the deferred request completion of an unhandled request", e);
      return false;
    }
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
    } catch (Exception e) {
      log.debug("Failed to commit blocking response", e);
      reportBlockFailure(reqCtx);
      return false;
    }
  }

  /** Returns the server span stored in the request context attribute, or {@code null}. */
  static AgentSpan spanFrom(Request request) {
    Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    return contextObj instanceof Context ? fromContext((Context) contextObj) : null;
  }

  /** Can run inside a Jetty write callback, so it never lets anything escape. */
  static void markEffectivelyBlocked(TraceSegment segment) {
    try {
      segment.effectivelyBlocked();
    } catch (Throwable t) {
      // the blocking response was already written, blocking succeeded
      log.debug("Failed to mark segment as effectively blocked", t);
    }
  }

  /** Can run inside a Jetty write callback, so it never lets anything escape. */
  static void reportBlockFailure(RequestContext reqCtx) {
    try {
      Object appSecCtx = reqCtx.getData(RequestContextSlot.APPSEC);
      if (appSecCtx instanceof AppSecContext) {
        ((AppSecContext) appSecCtx).reportBlockFailure();
      }
    } catch (Throwable t) {
      log.debug("Failed to report block failure", t);
    }
  }

  /** Client-caused I/O failures (e.g. a closed connection) are not reported to telemetry. */
  static void logWriteFailure(String message, Throwable failure) {
    if (failure instanceof IOException) {
      log.debug(EXCLUDE_TELEMETRY, message, failure);
    } else {
      log.debug(message, failure);
    }
  }
}
