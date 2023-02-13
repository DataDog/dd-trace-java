package datadog.trace.api.gateway;

import datadog.appsec.api.blocking.BlockingContentType;
import java.util.Map;

public interface BlockResponseFunction {
  boolean tryCommitBlockingResponse(
      int statusCode, BlockingContentType templateType, Map<String, String> extraHeaders);
}
