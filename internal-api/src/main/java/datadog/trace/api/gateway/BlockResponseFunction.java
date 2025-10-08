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
      String blockId,
      BlockingContentType templateType,
      Map<String, String> extraHeaders);
}
