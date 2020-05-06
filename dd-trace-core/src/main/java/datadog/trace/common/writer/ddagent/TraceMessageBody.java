package datadog.trace.common.writer.ddagent;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.nio.ByteBuffer;

public class TraceMessageBody extends RequestBody {

  private static final MediaType MSGPACK = MediaType.get("application/msgpack");

  private final ByteBuffer payload;

  public TraceMessageBody(ByteBuffer payload) {
    this.payload = payload;
  }

  @Override
  public MediaType contentType() {
    return MSGPACK;
  }

  @Override
  public long contentLength() {
    return payload.limit() - payload.position();
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(payload);
  }
}
