package datadog.trace.core.otlp.common;

import java.io.IOException;
import javax.annotation.Nonnull;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

/** Wraps an {@link OtlpPayload} as an OkHttp {@link RequestBody}. */
public final class OtlpHttpRequestBody extends RequestBody {

  private final OtlpPayload payload;
  private final MediaType mediaType;
  private final boolean gzip;

  public OtlpHttpRequestBody(OtlpPayload payload, boolean gzip) {
    this.payload = payload;
    this.mediaType = MediaType.get(payload.getContentType());
    this.gzip = gzip;
  }

  @Override
  public long contentLength() {
    return gzip ? -1 : payload.getContentLength();
  }

  @Override
  public MediaType contentType() {
    return mediaType;
  }

  @Override
  public void writeTo(@Nonnull BufferedSink sink) throws IOException {
    if (gzip) {
      try (BufferedSink gzipSink = Okio.buffer(new GzipSink(sink))) {
        payload.drain(gzipSink::write);
      }
    } else {
      payload.drain(sink::write);
    }
  }
}
