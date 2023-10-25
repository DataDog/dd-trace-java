package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.BufferedSink;

public final class ReadFromInputStreamJsonAdapter extends JsonAdapter<ByteArrayInputStream> {

  @Override
  public ByteArrayInputStream fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, ByteArrayInputStream inputStream) throws IOException {
    if (inputStream != null) {
      BufferedSink sink = writer.valueSink();
      byte[] bytes = getInputBytes(inputStream);
      sink.write(bytes);
      sink.flush();
    }
  }

  private byte[] getInputBytes(ByteArrayInputStream inputStream) throws IOException {
    inputStream.mark(0);
    byte[] bytes = new byte[inputStream.available()];
    inputStream.read(bytes);
    inputStream.reset();
    return bytes;
  }
}
