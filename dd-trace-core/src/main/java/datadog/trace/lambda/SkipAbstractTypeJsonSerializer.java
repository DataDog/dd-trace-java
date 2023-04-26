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
  private int stackCount;

  private SkipAbstractTypeJsonSerializer(JsonAdapter<T> delegate) {
    this.delegate = delegate;
    this.stackCount = 0;
  }

  @Override
  public T fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, T value) throws IOException {
    stackCount++;
    if(stackCount > 100) {
      // let's skip this item as we can't deserialize it as JSON
      skip(writer);
      return;
    }
    if(value != null) {
      if (value.getClass() instanceof Class<?> && (!Modifier.isAbstract(((Class<?>) value.getClass()).getModifiers()))) {
        try {
          writer.jsonValue(value);
          return;
        } catch (Exception e) {
          // nothing to do here
        }
      }
      if (isPlatformClass(value.getClass().getCanonicalName())) {
        skip(writer);
        return;
      }
    }
    delegate.toJson(writer, value);
  }

  private static boolean isPlatformClass(String canonicalName) {
    return canonicalName.startsWith("java.") || canonicalName.startsWith("javax.");
  }

  private static void skip(JsonWriter writer) throws IOException {
    writer.beginObject();
    writer.endObject();
  }

  public static <T> Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        if (!(requestedType instanceof Class<?>)) {
          return null;
        }
        if (Modifier.isAbstract(((Class<?>) requestedType).getModifiers()) || isPlatformClass(requestedType.getTypeName())) {
          JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
          return new SkipAbstractTypeJsonSerializer<>(delegate);
        }
        return null;
      }
    };
  }
}
