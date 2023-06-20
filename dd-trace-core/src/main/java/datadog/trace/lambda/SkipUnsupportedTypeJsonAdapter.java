package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

public final class SkipUnsupportedTypeJsonAdapter<T> extends JsonAdapter<T> {

  @Override
  public T fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, T value) throws IOException {
    // nothing to do when we deal with an unsupported type, let's skip it.
    writer.beginObject();
    writer.endObject();
  }

  public static Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        try {
          return moshi.nextAdapter(this, requestedType, annotations);
        } catch (IllegalArgumentException e) {
          return new SkipUnsupportedTypeJsonAdapter<>();
        }
      }
    };
  }
}
