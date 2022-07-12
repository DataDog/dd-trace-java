package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

public final class SkipTypeJsonSerializer<T> extends JsonAdapter<T> {
  private final JsonAdapter<T> delegate;
  private final String typeToSkip;

  private SkipTypeJsonSerializer(JsonAdapter<T> delegate, String typeToSkip) {
    this.delegate = delegate;
    this.typeToSkip = typeToSkip;
  }

  @Override
  public T fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, T value) throws IOException {
    if (null != value && value.getClass().getName().equals(typeToSkip)) {
      writer.beginObject();
      writer.endObject();
      return;
    }
    delegate.toJson(writer, value);
  }

  public static <T> Factory newFactory(final String typeToSkip) {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        if (requestedType instanceof Class<?>
            && ((Class<?>) requestedType).getName().equals(typeToSkip)) {
          JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
          return new SkipTypeJsonSerializer<>(delegate, typeToSkip);
        }
        return null;
      }
    };
  }
}
