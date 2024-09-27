package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

public class JsonStreamTraversal {

  public interface Visitor {
    /** @return - true to visit an object or an array, false to skip it */
    boolean visitInner(JsonPointer pointer);

    /** @return - true to visit the value, false to skip it */
    boolean visitValue(JsonPointer pointer);

    void valueVisited(JsonPointer pointer, Object value);

    /** @return - true to stop traversing, false to keep traversing */
    boolean keepTraversing();

    /** @return - true to expand the value, false to keep it as is */
    boolean expandValue(JsonPointer jsonPath, String raw);

    /** Called when parsing inner json failed */
    void expandValueFailed(JsonPointer jsonPath, String raw, Exception e);
  }

  public static void traverse(InputStream is, Visitor visitor, int depthLimit) throws IOException {
    JsonPointer pointer = new JsonPointer(depthLimit + 1);
    traverse(is, visitor, pointer);
  }

  private static void traverse(InputStream is, Visitor visitor, JsonPointer pointer)
      throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(is))) {
      try (JsonReader reader = JsonReader.of(source)) {
        reader.setLenient(true);
        traverse(reader, visitor, pointer);
      }
    }
  }

  private static void traverse(JsonReader reader, Visitor visitor, JsonPointer pointer)
      throws IOException {

    while (visitor.keepTraversing()) {

      switch (reader.peek()) {
        case END_DOCUMENT:
          return;

        case BEGIN_ARRAY:
          if (!visitValue(reader, visitor, pointer)) {
            pointer.endValue();
          } else if (visitor.visitInner(pointer)) {
            reader.beginArray();
            pointer.beginArray();
          } else {
            pointer.endValue();
            reader.skipValue();
          }
          break;

        case BEGIN_OBJECT:
          if (!visitValue(reader, visitor, pointer)) {
            pointer.endValue();
          } else if (visitor.visitInner(pointer)) {
            reader.beginObject();
          } else {
            pointer.endValue();
            reader.skipValue();
          }
          break;

        case NAME:
          String key = reader.nextName();
          pointer.name(key);
          break;

        case END_ARRAY:
          reader.endArray();
          pointer.endArray();
          pointer.endValue();
          break;

        case END_OBJECT:
          reader.endObject();
          pointer.endValue();
          break;

        case BOOLEAN:
          if (visitValue(reader, visitor, pointer)) {
            visitor.valueVisited(pointer, reader.nextBoolean());
          }
          pointer.endValue();
          break;

        case STRING:
          if (visitValue(reader, visitor, pointer)) {
            String raw = reader.nextString();
            if (!visitor.expandValue(pointer, raw)) {
              visitor.valueVisited(pointer, raw);
            } else if (visitor.visitInner(pointer)) {
              try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
                JsonPointer innerPath = pointer.copy(); // make a copy to prevent its modification
                traverse(is, visitor, innerPath);
              } catch (Exception e) {
                visitor.expandValueFailed(pointer, raw, e);
              }
            }
          }
          pointer.endValue();
          break;

        case NUMBER:
          if (visitValue(reader, visitor, pointer)) {
            // convert number to a string to preserve exact format
            visitor.valueVisited(pointer, reader.nextString());
          }
          pointer.endValue();
          break;

        case NULL:
          if (visitValue(reader, visitor, pointer)) {
            reader.nextNull();
            visitor.valueVisited(pointer, null);
          }
          pointer.endValue();
          break;
      }
    }
  }

  private static boolean visitValue(JsonReader reader, Visitor visitor, JsonPointer pointer)
      throws IOException {
    if (visitor.visitValue(pointer)) {
      return true;
    }
    reader.skipValue();
    return false;
  }
}
