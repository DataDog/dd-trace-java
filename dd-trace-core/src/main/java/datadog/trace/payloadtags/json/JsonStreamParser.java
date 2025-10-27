package datadog.trace.payloadtags.json;

import com.squareup.moshi.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

/**
 * Provides event-based parsing of JSON, iterating through the maps and arrays in a JSON structure.
 * Support for expanding embedded stringified JSON values, and stopping parsing before each
 * iteration.
 */
public class JsonStreamParser {

  public interface Visitor {
    /**
     * @return - true to visit the path, false to skip it
     */
    boolean visitCompound(PathCursor path);

    /**
     * @return - true to visit the path, false to skip it
     */
    boolean visitPrimitive(PathCursor path);

    void booleanValue(PathCursor path, boolean value);

    void stringValue(PathCursor path, String value);

    void intValue(PathCursor path, int value);

    void longValue(PathCursor path, long value);

    void doubleValue(PathCursor path, double value);

    void nullValue(PathCursor path);

    /**
     * Called at each iteration, so parsing can be stopped at any time
     *
     * @return - true to stop parsing, false to keep parsing
     */
    boolean keepParsing(PathCursor path);

    /**
     * Called when parsing of inner JSON-like value failed. It will be handled and continue parsing
     * the outer JSON-like value.
     */
    void expandValueFailed(PathCursor path, Exception exception);
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

    while (visitor.keepParsing(pathCursor)) {

      switch (reader.peek()) {
        case END_DOCUMENT:
          return;

        case BEGIN_ARRAY:
          if (visitor.visitCompound(pathCursor)) {
            reader.beginArray();
            pathCursor.push(0);
          } else {
            reader.skipValue();
            pathCursor.advance();
          }
          break;

        case BEGIN_OBJECT:
          if (visitor.visitCompound(pathCursor)) {
            reader.beginObject();
          } else {
            reader.skipValue();
            pathCursor.advance();
          }
          break;

        case NAME:
          String key = reader.nextName();
          pathCursor.push(key);
          break;

        case END_ARRAY:
          reader.endArray();
          pathCursor.pop();
          pathCursor.advance();
          break;

        case END_OBJECT:
          reader.endObject();
          pathCursor.advance();
          break;

        case BOOLEAN:
          if (visitor.visitPrimitive(pathCursor)) {
            visitor.booleanValue(pathCursor, reader.nextBoolean());
          } else {
            reader.skipValue();
          }
          pathCursor.advance();
          break;

        case STRING:
          if (!visitor.visitPrimitive(pathCursor)) {
            reader.skipValue();
          } else {
            String str = reader.nextString();
            if (!tryToParse(str, visitor, pathCursor)) {
              visitor.stringValue(pathCursor, str);
            }
          }
          pathCursor.advance();
          break;

        case NUMBER:
          if (!visitor.visitPrimitive(pathCursor)) {
            reader.skipValue();
          } else {
            String numberStr = reader.nextString();
            if (!tryToParseInt(visitor, pathCursor, numberStr)
                && !tryToParseLong(visitor, pathCursor, numberStr)
                && !tryToParseDouble(visitor, pathCursor, numberStr)) {
              visitor.stringValue(pathCursor, numberStr);
            }
          }
          pathCursor.advance();
          break;

        case NULL:
          if (visitor.visitPrimitive(pathCursor)) {
            reader.nextNull();
            visitor.nullValue(pathCursor);
          } else {
            reader.skipValue();
          }
          pathCursor.advance();
          break;
      }
    }
  }

  private static boolean tryToParseInt(Visitor visitor, PathCursor path, String str) {
    try {
      visitor.intValue(path, Integer.parseInt(str));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean tryToParseLong(Visitor visitor, PathCursor path, String str) {
    try {
      visitor.longValue(path, Long.parseLong(str));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean tryToParseDouble(Visitor visitor, PathCursor path, String str) {
    try {
      visitor.doubleValue(path, Double.parseDouble(str));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
