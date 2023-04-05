package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Set;

public final class SkipAbstractTypeJsonSerializer<T> extends JsonAdapter<T> {
  private final JsonAdapter<T> delegate;

  private SkipAbstractTypeJsonSerializer(JsonAdapter<T> delegate) {
    this.delegate = delegate;
  }

  @Override
  public T fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, T value) throws IOException {
    delegate.toJson(writer, value);
  }

  public static <T> Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        if (!(requestedType instanceof Class<?>)) {
          return null;
        }
        if (Modifier.isAbstract(((Class<?>) requestedType).getModifiers())) {
          JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
          return new SkipAbstractTypeJsonSerializer<>(delegate);
        }
        return null;
      }
    };
  }
}
