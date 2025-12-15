package datadog.trace.api.gateway;

import datadog.appsec.api.blocking.BlockingContentType;
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
      Map<String, String> extraHeaders);

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
   * @param action the blocking action containing status code, content type, and headers
   * @return true unless blocking could not be attempted
   */
  default boolean tryCommitBlockingResponse(
      TraceSegment segment, Flow.Action.RequestBlockingAction action) {
    return tryCommitBlockingResponse(
        segment, action.getStatusCode(), action.getBlockingContentType(), action.getExtraHeaders());
  }
}
