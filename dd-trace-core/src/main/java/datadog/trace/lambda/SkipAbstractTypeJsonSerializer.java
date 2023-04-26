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
    if(value.getClass().getCanonicalName().equals("java.io.ByteArrayInputStream")) {
      writer.beginObject();
      writer.endObject();
      return;
    }
    if (value != null && value.getClass() instanceof Class<?>) {
      System.out.println(value.getClass());
      if (!Modifier.isAbstract(((Class<?>) value.getClass()).getModifiers())) {
        try {
          writer.jsonValue(value);
          return;
        } catch (Exception e) {
          // nothing to do here
        }
      }
    }
    delegate.toJson(writer, value);
  }

  public static <T> Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        System.out.println(requestedType.getClass().getCanonicalName().startsWith("java."));
        if (requestedType.toString().equals("class java.io.ByteArrayInputStream")) {
          JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
          return new SkipAbstractTypeJsonSerializer<>(delegate);
        }
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
