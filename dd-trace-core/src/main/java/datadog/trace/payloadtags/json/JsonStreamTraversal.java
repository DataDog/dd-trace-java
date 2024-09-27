package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

public class JsonStreamTraversal {

  public interface Visitor {
    /** @return - true to skip an inner object or array, otherwise false */
    boolean skipInner(JsonPointer pointer);

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
          if (visitor.skipInner(pointer) || !visitor.visitValue(pointer)) {
            reader.skipValue();
            // an array can itself be a value in a parent array or object
            pointer.bumpIndexOrDropLast();
          } else {
            reader.beginArray();
            pointer.appendIndex();
          }
          break;

        case BEGIN_OBJECT:
          if (visitor.skipInner(pointer) || !visitor.visitValue(pointer)) {
            reader.skipValue();
            // an object can itself be a value in a parent array or object
            pointer.bumpIndexOrDropLast();
          } else {
            reader.beginObject();
          }
          break;

        case NAME:
          String key = reader.nextName();
          pointer.name(key);
          break;

        case END_ARRAY:
          reader.endArray();
          pointer.dropLast();
          // an array can itself be a value in a parent array or object
          pointer.bumpIndexOrDropLast();
          break;

        case END_OBJECT:
          reader.endObject();
          // an object can itself be a value in a parent array or object
          pointer.bumpIndexOrDropLast();
          break;

        case BOOLEAN:
          if (visitor.visitValue(pointer)) {
            boolean value = reader.nextBoolean();
            visitor.valueVisited(pointer, value);
          } else {
            reader.skipValue();
          }
          pointer.bumpIndexOrDropLast();
          break;

        case STRING:
          if (!visitor.visitValue(pointer)) {
            reader.skipValue();
          } else {
            String raw = reader.nextString();
            if (!visitor.expandValue(pointer, raw)) {
              visitor.valueVisited(pointer, raw);
            } else {
              try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
                // make a copy of the pointer to make sure it's in original position after
                // traversing inner json
                JsonPointer innerPath = pointer.copy();
                traverse(is, visitor, innerPath);
              } catch (Exception e) {
                visitor.expandValueFailed(pointer, raw, e);
              }
            }
          }
          pointer.bumpIndexOrDropLast();
          break;

        case NUMBER:
          if (visitor.visitValue(pointer)) {
            // read a number as a string to preserve exact format
            visitor.valueVisited(pointer, reader.nextString());
          } else {
            reader.skipValue();
          }
          pointer.bumpIndexOrDropLast();
          break;

        case NULL:
          if (visitor.visitValue(pointer)) {
            visitor.valueVisited(pointer, reader.nextNull());
          } else {
            reader.skipValue();
          }
          pointer.bumpIndexOrDropLast();
          break;
      }
    }
  }
}
