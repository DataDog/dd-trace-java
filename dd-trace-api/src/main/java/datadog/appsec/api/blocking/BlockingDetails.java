package datadog.appsec.api.blocking;

public class BlockingDetails {
  public final int statusCode;
  public final BlockingContentType blockingContentType;

  public BlockingDetails(int statusCode, BlockingContentType blockingContentType) {
    this.statusCode = statusCode;
    this.blockingContentType = blockingContentType;
  }
}
