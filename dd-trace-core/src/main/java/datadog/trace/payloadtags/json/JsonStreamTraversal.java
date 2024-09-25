package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

public class JsonStreamTraversal {

  public interface Visitor {
    /**
     * @param path
     * @return - true to handle object, false to skip it
     */
    boolean beforeObject(JsonPath.Builder path);

    void afterObject(JsonPath.Builder path);

    /**
     * @param path
     * @return - true to skip value, true to visit it
     */
    boolean skipValue(JsonPath path);

    /**
     * @param path
     * @param value
     */
    void visitValue(JsonPath path, Object value);

    boolean keepTraversing();
  }

  public static void traverse(InputStream is, Visitor visitor) throws IOException {
    JsonPath.Builder path = JsonPath.Builder.start();
    traverse(is, visitor, path);
  }

  private static void traverse(InputStream is, Visitor visitor, JsonPath.Builder path)
      throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(is))) {
      try (JsonReader reader = JsonReader.of(source)) {
        reader.setLenient(true);
        traverse(reader, visitor, path);
      }
    }
  }

  private static void traverse(JsonReader reader, Visitor visitor, JsonPath.Builder path)
      throws IOException {

    while (visitor.keepTraversing()) {

      switch (reader.peek()) {
        case END_DOCUMENT:
          return;

        case BEGIN_ARRAY:
          if (skipValue(reader, visitor, path)) {
            path.endValue();
          } else if (visitor.beforeObject(path)) {
            reader.beginArray();
            path.beginArray();
          } else {
            path.endValue();
            reader.skipValue();
          }
          break;

        case BEGIN_OBJECT:
          if (skipValue(reader, visitor, path)) {
            path.endValue();
          } else if (visitor.beforeObject(path)) {
            reader.beginObject();
          } else {
            path.endValue();
            reader.skipValue();
          }
          break;

        case NAME:
          String key = reader.nextName();
          path.key(key);
          break;

        case END_ARRAY:
          reader.endArray();
          path.endArray();
          visitor.afterObject(path);
          path.endValue();
          break;

        case END_OBJECT:
          reader.endObject();
          visitor.afterObject(path);
          path.endValue();
          break;

        case BOOLEAN:
          if (!skipValue(reader, visitor, path)) {
            visitor.visitValue(path.jsonPath(), reader.nextBoolean());
          }
          path.endValue();
          break;

        case STRING:
          if (!skipValue(reader, visitor, path)) {
            String raw = reader.nextString();
            // TODO add visitor beforeExpand, expandError, afterExpand
            boolean looksLikeJson =
                raw.startsWith("{") && raw.endsWith("}")
                    || raw.startsWith("[") && raw.endsWith("]");
            if (!looksLikeJson) {
              visitor.visitValue(path.jsonPath(), raw);
            } else if (visitor.beforeObject(path)) {
              try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
                JsonPath.Builder innerPath = path.copy(); // make a copy to prevent its modification
                traverse(is, visitor, innerPath);
              } catch (Exception e) {
                // TODO maybe debug log?
                visitor.visitValue(path.jsonPath(), raw);
              }
              visitor.afterObject(path);
            }
          }
          path.endValue();
          break;

        case NUMBER:
          if (!skipValue(reader, visitor, path)) {
            // convert number to a string to preserve exact format
            visitor.visitValue(path.jsonPath(), reader.nextString());
          }
          path.endValue();
          break;

        case NULL:
          if (!skipValue(reader, visitor, path)) {
            reader.nextNull();
            visitor.visitValue(path.jsonPath(), null);
          }
          path.endValue();
          break;
      }
    }
  }

  private static boolean skipValue(JsonReader reader, Visitor visitor, JsonPath.Builder path)
      throws IOException {
    if (visitor.skipValue(path.jsonPath())) {
      reader.skipValue();
      return true;
    }
    return false;
  }
}
