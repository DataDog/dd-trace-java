package datadog.trace.core.otlp.common;

import java.io.IOException;
import javax.annotation.Nonnull;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** Wraps an {@link OtlpPayload} as an OkHttp {@link RequestBody}. */
public final class OtlpHttpRequestBody extends RequestBody {

  private final OtlpPayload payload;
  private final MediaType mediaType;

  public OtlpHttpRequestBody(OtlpPayload payload) {
    this.payload = payload;
    this.mediaType = MediaType.get(payload.getContentType());
  }

  @Override
  public long contentLength() {
    return payload.getContentLength();
  }

  @Override
  public MediaType contentType() {
    return mediaType;
  }

  @Override
  public void writeTo(@Nonnull BufferedSink sink) throws IOException {
    payload.drain(sink::write);
  }
}
