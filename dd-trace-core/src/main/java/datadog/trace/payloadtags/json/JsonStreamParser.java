package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import datadog.trace.payloadtags.PathCursor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

/** Provides depth-first event-based parser of a JSON. */
public class JsonStreamParser {

  public interface Visitor {
    /**
     * Called before object or array value is parsed
     *
     * @return - true to instruct parser to skip an inner object or array, otherwise return false
     */
    boolean skipInner(PathCursor pathCursor); // TODO can be removed as visitValue does the same

    /** @return - true to visit the value, false to skip it */
    boolean visitValue(PathCursor pathCursor);

    /** Called when a primitive value is read */
    void valueVisited(
        PathCursor pathCursor,
        Object
            value); // TODO replace with typed methods boolean, string, number (maybe long/double),
    // null

    /**
     * Called at each iteration, so parsing can be stopped at any time
     *
     * @return - true to stop parsing, false to keep parsing
     */
    boolean keepParsing();

    /**
     * Called when parsing of inner JSON-like value failed. It will be handled and continue parsing
     * the outer JSON-like value.
     */
    void expandValueFailed(PathCursor jsonPath, Exception exception);
  }

  /**
   * Try to parse a JSON string if it looks like a JSON object or array.
   *
   * @return - true if the string was a JSON object or array, false if it was not JSON or failed to
   *     parse
   */
  public static boolean tryToParse(String raw, Visitor visitor, PathCursor pathCursor) {
    if (raw.startsWith("{") && raw.endsWith("}") || raw.startsWith("[") && raw.endsWith("]")) {
      try (InputStream is = new ByteArrayInputStream(raw.getBytes())) {
        return tryToParse(is, visitor, pathCursor.copy());
      } catch (Exception e) {
        visitor.expandValueFailed(pathCursor, e);
      }
    }
    return false;
  }

  /**
   * Try to parse an InputStream as JSON if it starts as a JSON object or array.
   *
   * @return - true if it was successfully parsed as JSON, false if it was not JSON, or it failed to
   *     parse.
   */
  public static boolean tryToParse(InputStream is, Visitor visitor, PathCursor pathCursor) {
    try (BufferedSource source = Okio.buffer(Okio.source(is))) {
      byte firstByte = source.peek().readByte();
      if (firstByte == '{' || firstByte == '[') {
        try (JsonReader reader = JsonReader.of(source)) {
          reader.setLenient(true);
          tryToParse(reader, visitor, pathCursor);
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

  private static void tryToParse(JsonReader reader, Visitor visitor, PathCursor pathCursor)
      throws IOException {

    while (visitor.keepParsing()) {

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
            if (!tryToParse(raw, visitor, pathCursor)) {
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
