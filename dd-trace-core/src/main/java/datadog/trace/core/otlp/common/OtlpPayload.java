package datadog.trace.core.otlp.common;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/** OTLP payload consisting of a sequence of chunked byte-arrays. */
public final class OtlpPayload {
  public static final OtlpPayload EMPTY = new OtlpPayload(new ArrayDeque<>(), 0, "");

  private final Deque<byte[]> chunks;
  private final int contentLength;
  private final String contentType;

  public OtlpPayload(Deque<byte[]> chunks, int contentLength, String contentType) {
    this.chunks = chunks;
    this.contentLength = contentLength;
    this.contentType = contentType;
  }

  /** Drains the chunked payload to the given sink. */
  public void drain(OtlpSink sink) throws IOException {
    byte[] chunk;
    while ((chunk = chunks.pollFirst()) != null) {
      sink.write(chunk);
    }
  }

  public int getContentLength() {
    return contentLength;
  }

  public String getContentType() {
    return contentType;
  }
}
