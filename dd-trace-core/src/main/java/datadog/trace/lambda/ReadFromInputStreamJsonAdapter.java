package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import okio.BufferedSink;

public final class ReadFromInputStreamJsonAdapter<T> extends JsonAdapter<T> {

  @Override
  public T fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, T value) throws IOException {
    BufferedSink sink = writer.valueSink();
    byte[] bytes = getInputBytes(value);
    sink.write(bytes);
    sink.flush();
  }

  private byte[] getInputBytes(T value) throws IOException {
    ByteArrayInputStream inputStream = (ByteArrayInputStream) value;
    inputStream.mark(0);
    byte[] bytes = new byte[inputStream.available()];
    inputStream.read(bytes);
    inputStream.reset();
    return bytes;
  }

  public static Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        if (requestedType == ByteArrayInputStream.class) {
          return new ReadFromInputStreamJsonAdapter<>();
        }
        return moshi.nextAdapter(this, requestedType, annotations);
      }
    };
  }
}