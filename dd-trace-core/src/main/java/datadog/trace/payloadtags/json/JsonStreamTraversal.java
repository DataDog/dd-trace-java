package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import datadog.trace.payloadtags.PathCursor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

public class JsonStreamTraversal {

  public interface Visitor {
    /** @return - true to skip an inner object or array, otherwise false */
    boolean skipInner(PathCursor pathCursor);

    /** @return - true to visit the value, false to skip it */
    boolean visitValue(PathCursor pathCursor);

    void valueVisited(PathCursor pathCursor, Object value);

    /** @return - true to stop traversing, false to keep traversing */
    boolean keepTraversing();

    /** Called when parsing inner json failed */
    void expandValueFailed(PathCursor jsonPath, Exception exception);
  }

  /**
   * Traverse a JSON string.
   *
   * @return - true if the string was a json object or array, false otherwise
   */
  public static boolean traverse(String raw, Visitor visitor, PathCursor pathCursor) {
    if (raw.startsWith("{") || raw.startsWith("[")) {
      try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
        return traverse(is, visitor, pathCursor.copy());
      } catch (Exception e) {
        visitor.expandValueFailed(pathCursor, e);
      }
    }
    return false;
  }

  /**
   * Traverse an InputStream as JSON.
   *
   * @return - true if it was successfully traversed as JSON, false otherwise
   */
  public static boolean traverse(InputStream is, Visitor visitor, PathCursor pathCursor) {
    try (BufferedSource source = Okio.buffer(Okio.source(is))) {
      byte firstByte = source.peek().readByte();
      if (firstByte == '{' || firstByte == '[') {
        try (JsonReader reader = JsonReader.of(source)) {
          reader.setLenient(true);
          traverse(reader, visitor, pathCursor);
          return true;
        } catch (Exception e) {
          visitor.expandValueFailed(pathCursor, e);
        }
      }
    } catch (Exception e) {
      visitor.expandValueFailed(pathCursor, e);
    }
    return false;
  }

  private static void traverse(JsonReader reader, Visitor visitor, PathCursor pathCursor)
      throws IOException {

    while (visitor.keepTraversing()) {

      switch (reader.peek()) {
        case END_DOCUMENT:
          return;

        case BEGIN_ARRAY:
          if (visitor.skipInner(pathCursor) || !visitor.visitValue(pathCursor)) {
            reader.skipValue();
            // an array can itself be a value in a parent array or object
            pathCursor.advance();
          } else {
            reader.beginArray();
            pathCursor.push(0);
          }
          break;

        case BEGIN_OBJECT:
          if (visitor.skipInner(pathCursor) || !visitor.visitValue(pathCursor)) {
            reader.skipValue();
            // an object can itself be a value in a parent array or object
            pathCursor.advance();
          } else {
            reader.beginObject();
          }
          break;

        case NAME:
          String key = reader.nextName();
          pathCursor.push(key);
          break;

        case END_ARRAY:
          reader.endArray();
          pathCursor.pop();
          // an array can itself be a value in a parent array or object
          pathCursor.advance();
          break;

        case END_OBJECT:
          reader.endObject();
          // an object can itself be a value in a parent array or object
          pathCursor.advance();
          break;

        case BOOLEAN:
          if (visitor.visitValue(pathCursor)) {
            boolean value = reader.nextBoolean();
            visitor.valueVisited(pathCursor, value);
          } else {
            reader.skipValue();
          }
          pathCursor.advance();
          break;

        case STRING:
          if (!visitor.visitValue(pathCursor)) {
            reader.skipValue();
          } else {
            String raw = reader.nextString();
            if (!traverse(raw, visitor, pathCursor)) {
              visitor.valueVisited(pathCursor, raw);
            }
          }
          pathCursor.advance();
          break;

        case NUMBER:
          if (visitor.visitValue(pathCursor)) {
            // read a number as a string to preserve exact format
            visitor.valueVisited(pathCursor, reader.nextString());
          } else {
            reader.skipValue();
          }
          pathCursor.advance();
          break;

        case NULL:
          if (visitor.visitValue(pathCursor)) {
            visitor.valueVisited(pathCursor, reader.nextNull());
          } else {
            reader.skipValue();
          }
          pathCursor.advance();
          break;
      }
    }
  }
}
