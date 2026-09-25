package datadog.trace.api.gateway;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.internal.TraceSegment;
import java.util.Map;

public interface BlockResponseFunction {
  /**
   * Commits blocking response.
   *
   * <p>It's responsible for calling {@link TraceSegment#effectivelyBlocked()} before the span is
   * finished.
   *
   * @return true unless blocking could not be attempted
   */
  boolean tryCommitBlockingResponse(
      TraceSegment segment,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders,
      String securityResponseId);

  /**
   * Commits blocking response using a RequestBlockingAction.
   *
   * <p>This method delegates to the parameter-based method by default, extracting individual fields
   * from the RequestBlockingAction. Implementations can override this for more efficient
   * processing.
   *
   * <p>It's responsible for calling {@link TraceSegment#effectivelyBlocked()} before the span is
   * finished.
   *
   * @param segment the trace segment
   * @param action the blocking action containing status code, content type, headers, and security
   *     response ID
   * @return true unless blocking could not be attempted
   */
  default boolean tryCommitBlockingResponse(
      TraceSegment segment, Flow.Action.RequestBlockingAction action) {
    return tryCommitBlockingResponse(
        segment,
        action.getStatusCode(),
        action.getBlockingContentType(),
        action.getExtraHeaders(),
        action.getSecurityResponseId());
  }

  /**
   * Commits blocking response using a RequestBlockingAction, reporting to {@link
   * AppSecContext#reportBlockFailure()} if the commit fails.
   *
   * <p>It's responsible for calling {@link TraceSegment#effectivelyBlocked()} before the span is
   * finished.
   *
   * @param ctx the request context
   * @param action the blocking action containing status code, content type, headers, and security
   *     response ID
   * @return true unless blocking could not be attempted
   */
  default boolean tryCommitBlockingResponse(
      RequestContext ctx, Flow.Action.RequestBlockingAction action) {
    boolean committed = tryCommitBlockingResponse(ctx.getTraceSegment(), action);
    if (!committed) {
      Object rawAppSecCtx = ctx.getData(RequestContextSlot.APPSEC);
      if (rawAppSecCtx instanceof AppSecContext) {
        ((AppSecContext) rawAppSecCtx).reportBlockFailure();
      }
    }
    return committed;
  }
}
