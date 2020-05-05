package datadog.trace.common.writer.ddagent;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.nio.ByteBuffer;

public class RawMessageBody extends RequestBody {

  private static final MediaType MSGPACK = MediaType.get("application/msgpack");

  private final ByteBuffer payload;
  private final int traceCount;

  public RawMessageBody(ByteBuffer payload, int traceCount) {
    this.payload = payload;
    this.traceCount = traceCount;
  }

  @Override
  public MediaType contentType() {
    return MSGPACK;
  }

  @Override
  public long contentLength() {
    return traceCount;
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(payload);
  }
}
