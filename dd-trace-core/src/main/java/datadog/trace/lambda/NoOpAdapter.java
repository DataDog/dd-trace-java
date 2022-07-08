package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

public final class NoOpAdapter<T> extends JsonAdapter<T> {
  private final JsonAdapter<T> delegate;

  private NoOpAdapter(JsonAdapter<T> delegate) {
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

  public static <T> Factory newFactory(final String type) {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        if (!requestedType.toString().endsWith(type)) {
          return null;
        }
        JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
        return new NoOpAdapter<>(delegate);
      }
    };
  }
}
