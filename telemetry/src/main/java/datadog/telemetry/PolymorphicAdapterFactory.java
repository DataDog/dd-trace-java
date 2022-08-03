package datadog.telemetry;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import javax.annotation.Nullable;

class PolymorphicAdapterFactory implements JsonAdapter.Factory {
  private final Class<?> parentClass;

  PolymorphicAdapterFactory(Class<?> parentClass) {
    this.parentClass = parentClass;
  }

  @Nullable
  @Override
  public JsonAdapter<?> create(
      Type type, Set<? extends Annotation> annotations, final Moshi moshi) {
    Class<?> rawType = Types.getRawType(type);
    if (rawType != parentClass) {
      return null;
    }

    return new JsonAdapter<Object>() {
      @Override
      public Object fromJson(JsonReader reader) {
        return null;
      }

      @Override
      public void toJson(JsonWriter writer, @Nullable Object value) throws IOException {
        if (value == null) {
          writer.nullValue();
          return;
        }

        Class<?> actualClass = value.getClass();
        JsonAdapter<?> adapter = moshi.adapter(actualClass);
        ((JsonAdapter<Object>) adapter).toJson(writer, value);
      }
    };
  }
}
