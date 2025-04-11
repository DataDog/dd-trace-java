package datadog.json;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A lightweight JSON reader without dependencies. It performs minimal JSON type and structure
 * checks unless using the lenient mode.
 */
public class JsonReader implements AutoCloseable {
  private static final int BUFFER_SIZE = 1024;
  private final Reader reader;
  private final char[] buffer = new char[BUFFER_SIZE];
  private final JsonStructure structure;
  private int position = 0;
  private int limit = 0;
  private int lineNumber = 1;
  private int linePosition = 0;

  /** Creates a reader with structure check. */
  public JsonReader(String json) {
    this(new StringReader(json), true);
  }

  /** Creates a reader with structure check. */
  public JsonReader(Reader reader) {
    this(reader, true);
  }

  /**
   * Creates a reader.
   *
   * @param reader The source reader.
   * @param safe {@code true} to use safe structure check, {@code false} for lenient mode.
   */
  public JsonReader(Reader reader, boolean safe) {
    if (reader == null) {
      throw new IllegalArgumentException("reader cannot be null");
    }
    this.reader = reader;
    this.structure = safe ? new SafeJsonStructure() : new LenientJsonStructure();
  }

  /**
   * Begins reading a JSON object.
   *
   * @return This reader instance.
   * @throws IOException If an I/O error occurs.
   */
  public JsonReader beginObject() throws IOException {
    char c = advanceUpToNextValueChar();
    if (c != '{') {
      throw unexpectedSyntaxError("'{'", c);
    }
    advance();
    this.structure.beginObject();
    return this;
  }

  /**
   * Ends reading a JSON object.
   *
   * @return This reader instance.
   * @throws IOException If an I/O error occurs.
   */
  public JsonReader endObject() throws IOException {
    consumeWhitespace();
    char c = peek();
    if (c != '}') {
      throw unexpectedSyntaxError("'}'", c);
    }
    advance();
    this.structure.endObject();
    return this;
  }

  /**
   * Begins reading a JSON array.
   *
   * @return This reader instance.
   * @throws IOException If an I/O error occurs.
   */
  public JsonReader beginArray() throws IOException {
    char c = advanceUpToNextValueChar();
    if (c != '[') {
      throw unexpectedSyntaxError("'['", c);
    }
    advance();
    this.structure.beginArray();
    return this;
  }

  /**
   * Ends reading a JSON array.
   *
   * @return This reader instance.
   * @throws IOException If an I/O error occurs.
   */
  public JsonReader endArray() throws IOException {
    consumeWhitespace();
    char c = peek();
    if (c != ']') {
      throw unexpectedSyntaxError("']'", c);
    }
    advance();
    this.structure.endArray();
    return this;
  }

  /**
   * Reads the next property name in an object.
   *
   * @return The property name or null if the object is empty or ended.
   * @throws IOException If an I/O error occurs.
   */
  public String nextName() throws IOException {
    char c = advanceUpToNextValueChar();
    if (c == '}') {
      return null;
    }
    if (c != '"') {
      throw unexpectedSyntaxError("'\"'", c);
    }
    String name = readString();
    consumeWhitespace();
    c = peek();
    if (c != ':') {
      throw unexpectedSyntaxError("':'", c);
    }
    advance();
    this.structure.addName();
    return name;
  }

  /**
   * Checks if the current value is null.
   *
   * @return true if the value is null.
   * @throws IOException If an I/O error occurs.
   */
  public boolean isNull() throws IOException {
    return advanceUpToNextValueChar() == 'n';
  }

  /**
   * Reads a boolean value.
   *
   * @return The boolean value.
   * @throws IOException If an I/O error occurs.
   */
  public boolean nextBoolean() throws IOException {
    char c = advanceUpToNextValueChar();
    String value;
    if (c == 't') {
      value = readLiteral(4);
      if (!"true".equals(value)) {
        throw unexpectedSyntaxError("'true'", value);
      }
      return true;
    } else if (c == 'f') {
      value = readLiteral(5);
      if (!"false".equals(value)) {
        throw unexpectedSyntaxError("'false'", value);
      }
      return false;
    }
    throw unexpectedSyntaxError("'true' or 'false", c);
  }

  /**
   * Reads a string value.
   *
   * @return The string value.
   * @throws IOException If an I/O error occurs.
   */
  public String nextString() throws IOException {
    char c = advanceUpToNextValueChar();
    if (c != '"') {
      throw unexpectedSyntaxError("'\"'", c);
    }
    return readString();
  }

  /**
   * Reads a number as an int value.
   *
   * @return The int value.
   * @throws IOException If the value is not an int, or a reader error occurs.
   */
  public int nextInt() throws IOException {
    String number = readNumber();
    try {
      return Integer.parseInt(number);
    } catch (NumberFormatException e) {
      throw unexpectedSyntaxError("an integer", number);
    }
  }

  /**
   * Reads a number as a long value.
   *
   * @return The long value.
   * @throws IOException If the value is not a long, or a reader error occurs.
   */
  public long nextLong() throws IOException {
    String number = readNumber();
    try {
      return Long.parseLong(number);
    } catch (NumberFormatException e) {
      throw unexpectedSyntaxError("a long", number);
    }
  }

  /**
   * Reads a number as a double value.
   *
   * @return The double value.
   * @throws IOException If an I/O error occurs.
   */
  public double nextDouble() throws IOException {
    String number = readNumber();
    try {
      return Double.parseDouble(number);
    } catch (NumberFormatException e) {
      throw unexpectedSyntaxError("a double", number);
    }
  }

  /**
   * Checks if there are more elements in the current array or object.
   *
   * @return {@code true} if there are more elements, {@code false} otherwise.
   * @throws IOException If an I/O error occurs.
   */
  public boolean hasNext() throws IOException {
    consumeWhitespace();
    char c = peek();
    return c != ']' && c != '}';
  }

  /**
   * Reads the next value and automatically detects its type.
   *
   * <p>Supported types are:
   *
   * <ul>
   *   <li>{@link String} for quoted values
   *   <li>{@link Boolean} for {@code true}/{@code false}
   *   <li>{@link Integer}/{@link Long}/{@link Double} for numbers
   *   <li>{@link Map} for objects
   *   <li>{@link List} for arrays
   * </ul>
   *
   * @return The next value as its related Java type.
   * @throws IOException If the JSON is invalid, or a reader error occurs.
   */
  public Object nextValue() throws IOException {
    char c = advanceUpToNextValueChar();
    switch (c) {
      case '"':
        return nextString();
      case '{':
        Map<String, Object> map = new HashMap<>();
        beginObject();
        String name;
        while ((name = nextName()) != null) {
          map.put(name, nextValue());
        }
        endObject();
        return map;
      case '[':
        List<Object> list = new ArrayList<>();
        beginArray();
        while (hasNext()) {
          list.add(nextValue());
        }
        endArray();
        return list;
      case 't':
      case 'f':
        return nextBoolean();
      case 'n':
        readLiteral(4); // Skip "null"
        return null;
      case '-':
      case '0':
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '8':
      case '9':
        String number = readNumber();
        // Try parsing as integer first, then long, then fall back to double
        if (!number.contains(".") && !number.contains("e") && !number.contains("E")) {
          try {
            return Integer.parseInt(number);
          } catch (NumberFormatException e) {
            try {
              return Long.parseLong(number);
            } catch (NumberFormatException e2) {
              // Fall through to double
            }
          }
        }
        return Double.parseDouble(number);
      default:
        throw syntaxError("Unexpected character: " + c);
    }
  }

  private char advanceUpToNextValueChar() throws IOException {
    consumeWhitespace();
    char c = peek();
    // Handle comma between values
    if (c == ',') {
      advance(); // Skip comma
      consumeWhitespace();
      c = peek();
    }
    return c;
  }

  private void consumeWhitespace() throws IOException {
    while (true) {
      char c = peek();
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
        advance();
        if (c == '\n') {
          this.lineNumber++;
          this.linePosition = 0;
        }
      } else {
        break;
      }
    }
  }

  private char peek() throws IOException {
    if (this.position >= this.limit) {
      fillBuffer();
    }
    return this.position < this.limit ? this.buffer[this.position] : '\0';
  }

  private void advance() throws IOException {
    if (this.position >= this.limit) {
      fillBuffer();
    }
    if (this.position < this.limit) {
      this.position++;
      this.linePosition++;
    }
  }

  private void fillBuffer() throws IOException {
    this.limit = this.reader.read(this.buffer, 0, this.buffer.length);
    this.position = 0;
    if (this.limit == -1) {
      this.limit = 0;
    }
  }

  private String readString() throws IOException {
    StringBuilder sb = new StringBuilder();
    advance(); // Skip opening quote
    while (true) {
      char c = peek();
      if (c == '"') {
        advance();
        break;
      }
      if (c == '\\') {
        advance();
        c = peek();
        switch (c) {
          case '"':
          case '\\':
          case '/':
            sb.append(c);
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'u':
            sb.append(readUnicodeEscape());
            continue;
          default:
            throw syntaxError("Invalid escape sequence: \\" + c);
        }
      } else if (c < ' ') {
        throw syntaxError("Unterminated string");
      } else {
        sb.append(c);
      }
      advance();
    }
    return sb.toString();
  }

  private char readUnicodeEscape() throws IOException {
    advance(); // Skip 'u'
    StringBuilder hex = new StringBuilder(4);
    for (int i = 0; i < 4; i++) {
      hex.append(peek());
      advance();
    }
    try {
      return (char) Integer.parseInt(hex.toString(), 16);
    } catch (NumberFormatException e) {
      throw syntaxError("Invalid unicode escape sequence: \\u" + hex);
    }
  }

  private String readNumber() throws IOException {
    StringBuilder sb = new StringBuilder();
    char c = peek();
    // Optional minus sign
    if (c == '-') {
      sb.append(c);
      advance();
    }
    // Integer part
    readDigits(sb);
    // Fractional part
    if (peek() == '.') {
      sb.append('.');
      advance();
      readDigits(sb);
    }
    // Exponent part
    c = peek();
    if (c == 'e' || c == 'E') {
      sb.append(c);
      advance();
      c = peek();
      if (c == '+' || c == '-') {
        sb.append(c);
        advance();
      }
      readDigits(sb);
    }
    return sb.toString();
  }

  private void readDigits(StringBuilder sb) throws IOException {
    char c = peek();
    if (c < '0' || c > '9') {
      throw unexpectedSyntaxError("digit", c);
    }
    while (true) {
      c = peek();
      if (c < '0' || c > '9') {
        break;
      }
      sb.append(c);
      advance();
    }
  }

  private String readLiteral(int length) throws IOException {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(peek());
      advance();
    }
    return sb.toString();
  }

  private IOException unexpectedSyntaxError(String expected, String found) {
    return new IOException(
        String.format(
            "Syntax error at line %d, position %d: expected %s but found '%s'",
            this.lineNumber, this.linePosition, expected, found));
  }

  private IOException unexpectedSyntaxError(String expected, char found) {
    return new IOException(
        String.format(
            "Syntax error at line %d, position %d: expected %s but found '%s'",
            this.lineNumber, this.linePosition, expected, found));
  }

  private IOException syntaxError(String message) {
    return new IOException(
        String.format(
            "Syntax error at line %d, position %d: %s",
            this.lineNumber, this.linePosition, message));
  }

  @Override
  public void close() throws IOException {
    this.reader.close();
  }
}
