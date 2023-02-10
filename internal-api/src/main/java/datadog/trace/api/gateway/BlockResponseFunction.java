package datadog.trace.api.gateway;

import datadog.appsec.api.blocking.BlockingContentType;

public interface BlockResponseFunction {
  boolean tryCommitBlockingResponse(int statusCode, BlockingContentType templateType);
}
