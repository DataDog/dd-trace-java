package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;

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
    // nothing to do when we deal with an unsupported type, let's skip it.
    writer.beginObject();
    writer.endObject();
  }

  private static boolean isPlatformClass(String canonicalName) {
    return canonicalName.startsWith("java.") || canonicalName.startsWith("javax.");
  }

  private static boolean isInAllowList(String canonicalName) {
    return canonicalName.equals(List.class.getCanonicalName())
        || canonicalName.equals(Map.class.getCanonicalName())
        || canonicalName.equals(Set.class.getCanonicalName())
        || canonicalName.equals(Collection.class.getCanonicalName())
        || canonicalName.equals(Object.class.getCanonicalName())
        || canonicalName.equals(Integer.class.getCanonicalName())
        || canonicalName.equals(Double.class.getCanonicalName())
        || canonicalName.equals(Long.class.getCanonicalName())
        || canonicalName.equals(String.class.getCanonicalName())
        || canonicalName.equals(Boolean.class.getCanonicalName())
        || canonicalName.equals(Float.class.getCanonicalName())
        || canonicalName.equals("boolean")
        || canonicalName.equals("char")
        || canonicalName.equals("short")
        || canonicalName.equals("double")
        || canonicalName.equals("byte")
        || canonicalName.equals("float")
        || canonicalName.equals("int");
  }

  public static <T> Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        boolean isClass = requestedType instanceof Class<?>;
        String typeName = isClass ? ((Class<?>) requestedType).getTypeName() : "";
        boolean isAbstract =
            isClass
                && Modifier.isAbstract(((Class<?>) requestedType).getModifiers())
                && !isInAllowList(typeName);
        boolean isPlatform = isClass && isPlatformClass(typeName);
        boolean isInAllowList = isClass && isInAllowList(typeName);
        if (isAbstract || (isPlatform && !isInAllowList)) {
          JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
          return new SkipAbstractTypeJsonSerializer<>(delegate);
        }
        return null;
      }
    };
  }
}
