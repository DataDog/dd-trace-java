package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

public class JsonStreamTraversal {

  public interface Visitor {
    /** @return - true to visit the object, false to skip it */
    boolean visitObject(JsonPath.Builder path);

    /** @return - true to visit the value, false to skip it */
    boolean visitValue(JsonPath path);

    void valueVisited(JsonPath path, Object value);

    /** @return - true to stop traversing, false to keep traversing */
    boolean keepTraversing();

    boolean expandValue(JsonPath jsonPath, String raw);

    void expandValueFailed(JsonPath jsonPath, String raw, Exception e);
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
          if (!visitValue(reader, visitor, path)) {
            path.endValue();
          } else if (visitor.visitObject(path)) {
            reader.beginArray();
            path.beginArray();
          } else {
            path.endValue();
            reader.skipValue();
          }
          break;

        case BEGIN_OBJECT:
          if (!visitValue(reader, visitor, path)) {
            path.endValue();
          } else if (visitor.visitObject(path)) {
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
          path.endValue();
          break;

        case END_OBJECT:
          reader.endObject();
          path.endValue();
          break;

        case BOOLEAN:
          if (visitValue(reader, visitor, path)) {
            visitor.valueVisited(path.jsonPath(), reader.nextBoolean());
          }
          path.endValue();
          break;

        case STRING:
          if (visitValue(reader, visitor, path)) {
            String raw = reader.nextString();
            if (!visitor.expandValue(path.jsonPath(), raw)) {
              visitor.valueVisited(path.jsonPath(), raw);
            } else if (visitor.visitObject(path)) {
              try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
                JsonPath.Builder innerPath = path.copy(); // make a copy to prevent its modification
                traverse(is, visitor, innerPath);
              } catch (Exception e) {
                visitor.expandValueFailed(path.jsonPath(), raw, e);
              }
            }
          }
          path.endValue();
          break;

        case NUMBER:
          if (visitValue(reader, visitor, path)) {
            // convert number to a string to preserve exact format
            visitor.valueVisited(path.jsonPath(), reader.nextString());
          }
          path.endValue();
          break;

        case NULL:
          if (visitValue(reader, visitor, path)) {
            reader.nextNull();
            visitor.valueVisited(path.jsonPath(), null);
          }
          path.endValue();
          break;
      }
    }
  }

  private static boolean visitValue(JsonReader reader, Visitor visitor, JsonPath.Builder path)
      throws IOException {
    if (visitor.visitValue(path.jsonPath())) {
      return true;
    }
    reader.skipValue();
    return false;
  }
}
