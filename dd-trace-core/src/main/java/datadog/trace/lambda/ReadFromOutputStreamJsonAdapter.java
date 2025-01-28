package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import okio.BufferedSink;

public final class ReadFromOutputStreamJsonAdapter extends JsonAdapter<ByteArrayOutputStream> {

  @Override
  public ByteArrayOutputStream fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, ByteArrayOutputStream outputStream) throws IOException {
    if (outputStream != null) {
      BufferedSink sink = writer.valueSink();
      byte[] bytes = outputStream.toByteArray();
      sink.write(bytes);
      sink.flush();
    }
  }
}
