package datadog.appsec.api.blocking;

import java.util.Map;

public class BlockingDetails {
  public final int statusCode;
  public final BlockingContentType blockingContentType;
  public final Map<String, String> extraHeaders;

  public BlockingDetails(
      int statusCode, BlockingContentType blockingContentType, Map<String, String> extraHeaders) {
    this.statusCode = statusCode;
    this.blockingContentType = blockingContentType;
    this.extraHeaders = extraHeaders;
  }
}
