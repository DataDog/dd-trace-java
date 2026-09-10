package datadog.trace.core.otlp.common;

import java.nio.ByteBuffer;

public final class OtlpPayload {
  public static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";
  public static final String JSON_CONTENT_TYPE = "application/json";

  public static final OtlpPayload EMPTY = new OtlpPayload(ByteBuffer.allocate(0), "");

  private final ByteBuffer content;
  private final int contentLength;
  private final String contentType;

  public OtlpPayload(ByteBuffer content, String contentType) {
    this.content = content;
    this.contentLength = content.remaining();
    this.contentType = contentType;
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
}
