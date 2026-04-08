package datadog.trace.core.otlp.common;

import java.io.IOException;
import javax.annotation.Nonnull;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

/** Wraps an {@link OtlpPayload} as a GRPC {@link RequestBody}. */
public final class OtlpGrpcRequestBody extends RequestBody {

  private static final MediaType GRPC_MEDIA_TYPE = MediaType.parse("application/grpc");

  private static final int HEADER_LENGTH = 5;

  private static final byte UNCOMPRESSED_FLAG = 0;
  private static final byte COMPRESSED_FLAG = 1;

  private final OtlpPayload payload;
  private final boolean gzip;

  public OtlpGrpcRequestBody(OtlpPayload payload, boolean gzip) {
    this.payload = payload;
    this.gzip = gzip;
  }

  @Override
  public long contentLength() {
    return gzip ? -1 : HEADER_LENGTH + payload.getContentLength();
  }

  @Override
  public MediaType contentType() {
    return GRPC_MEDIA_TYPE;
  }

  @Override
  public void writeTo(@Nonnull BufferedSink sink) throws IOException {
    if (gzip) {
      try (Buffer gzipBody = new Buffer()) {
        try (BufferedSink gzipSink = Okio.buffer(new GzipSink(gzipBody))) {
          payload.drain(gzipSink::write);
        }
        sink.writeByte(COMPRESSED_FLAG);
        long gzipLength = gzipBody.size();
        sink.writeInt((int) gzipLength);
        sink.write(gzipBody, gzipLength);
      }
    } else {
      sink.writeByte(UNCOMPRESSED_FLAG);
      sink.writeInt(payload.getContentLength());
      payload.drain(sink::write);
    }
  }
}
