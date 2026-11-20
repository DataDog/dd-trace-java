package datadog.trace.core.otlp.common;

import java.nio.ByteBuffer;

public final class OtlpPayload {
  public static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";
  public static final String JSON_CONTENT_TYPE = "application/json";

  public static final OtlpPayload EMPTY = new OtlpPayload(ByteBuffer.allocate(0), "");

  private final ByteBuffer content;
  private final int contentLength;
  private final String contentType;
  private final int itemCount;

  public OtlpPayload(ByteBuffer content, String contentType) {
    this(content, contentType, 0);
  }

  /**
   * @param itemCount the number of telemetry items encoded in {@code content}, such as metric data
   *     points; 0 when the producer doesn't count them
   */
  public OtlpPayload(ByteBuffer content, String contentType, int itemCount) {
    this.content = content;
    this.contentLength = content.remaining();
    this.contentType = contentType;
    this.itemCount = itemCount;
  }

  public ByteBuffer getContent() {
    return content.asReadOnlyBuffer();
  }

  public int getContentLength() {
    return contentLength;
  }

  public String getContentType() {
    return contentType;
  }

  /** The number of telemetry items in the payload, or 0 when the producer doesn't count them. */
  public int getItemCount() {
    return itemCount;
  }
}
