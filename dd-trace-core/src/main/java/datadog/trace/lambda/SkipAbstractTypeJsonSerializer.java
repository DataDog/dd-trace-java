package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

  private static final HashSet<String> allowedCanonicalNames =
      Stream.of(
              "java.util.List",
              "java.util.Map",
              "java.util.Set",
              "java.util.Collection",
              "java.lang.Object",
              "java.lang.Integer",
              "java.lang.Double",
              "java.lang.Long",
              "java.lang.Short",
              "java.lang.Float",
              "java.lang.Boolean",
              "java.lang.Byte",
              "java.lang.Character",
              "java.lang.String",
              "int",
              "double",
              "long",
              "short",
              "float",
              "boolean",
              "byte",
              "char")
          .collect(Collectors.toCollection(HashSet::new));

  public static <T> Factory newFactory() {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          Type requestedType, Set<? extends Annotation> annotations, Moshi moshi) {
        if (!(requestedType instanceof Class<?>)) {
          return null;
        }
        String typeName = ((Class<?>) requestedType).getTypeName();
        if (allowedCanonicalNames.contains(typeName)) {
          return null;
        }
        boolean isAbstract = Modifier.isAbstract(((Class<?>) requestedType).getModifiers());
        if (isAbstract || isPlatformClass(typeName)) {
          JsonAdapter<T> delegate = moshi.nextAdapter(this, Object.class, annotations);
          return new SkipAbstractTypeJsonSerializer<>(delegate);
        }
        return null;
      }
    };
  }
}
