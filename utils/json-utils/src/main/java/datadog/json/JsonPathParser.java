package datadog.json;

import static java.lang.Character.isDigit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonPathParser {

  public static final class ParseError extends Exception {
    public final int position;
    public final String error;

    public ParseError(CharSequence path, int position, String error) {
      super("Failed to parse JsonPath \"" + path + "\" at " + position + ". " + error);
      this.position = position;
      this.error = error;
    }
  }

  private static final char OPEN_BRACKET = '[';
  private static final char CLOSE_BRACKET = ']';
  private static final char OPEN_BRACE = '(';
  private static final char ASTERISK = '*';
  private static final char PERIOD = '.';
  private static final char SPACE = ' ';
  private static final char COMMA = ',';
  private static final char SINGLE_QUOTE = '\'';
  private static final char DOUBLE_QUOTE = '"';
  private static final char ESC = '\\';

  private static final Logger log = LoggerFactory.getLogger(JsonPathParser.class);

  public static List<JsonPath> parseJsonPaths(List<String> rules) {
    if (rules.isEmpty() || rules.size() == 1 && rules.get(0).equalsIgnoreCase("all")) {
      return Collections.emptyList();
    }
    List<JsonPath> result = new ArrayList<>(rules.size());
    for (String rule : rules) {
      try {
        JsonPath jp = JsonPathParser.parse(rule);
        result.add(jp);
      } catch (Exception ex) {
        log.warn("Skipping failed to parse redaction rule '{}'. {}", rule, ex.getMessage());
      }
    }
    return result;
  }

  public static JsonPath parse(String path) throws ParseError {
    Cursor cur = new Cursor(path);

    if (!(path.startsWith("$.") || path.startsWith("$["))) {
      cur.fail("JsonPath must start with '$.' or '$['");
    }

    JsonPath.Builder builder = JsonPath.Builder.start();
    cur.incPos(1);

    while (cur.isWithinLimits()) {
      if (cur.is(OPEN_BRACKET)) {
        boolean ok =
            tryToParsePropertyInBrackets(cur, builder)
                || tryToParseIndex(cur, builder)
                || tryToParseWildcard(cur, builder);
        if (!ok) {
          cur.fail("Expecting in brackets a property, an array index, or a wildcard.");
        }
      } else if (cur.is(PERIOD)) {
        if (cur.nextIs(PERIOD)) {
          builder.anyDesc();
          cur.incPos(1);
        } else if (cur.nextIs(OPEN_BRACKET)) {
          cur.fail("'" + PERIOD + "' can't go before '" + OPEN_BRACKET + "'");
        }
        if (!cur.hasMoreCharacters()) {
          cur.fail("Path must not end with a '" + PERIOD + "'");
        } else {
          cur.incPos(1);
        }
        if (cur.is(PERIOD)) {
          cur.fail("More than two '" + PERIOD + "' in a row");
        }
      } else if (cur.is(ASTERISK)) {
        if (cur.nextIs(ASTERISK)) {
          cur.fail("More than one '" + ASTERISK + "' in a row");
        }
        cur.incPos(1);
        builder.anyChild();
      } else {
        parseKey(cur, builder);
      }
    }
    return builder.build();
  }

  private static void parseKey(Cursor cur, JsonPath.Builder builder) throws ParseError {
    int start = cur.position();
    int i = start;
    int end = 0;

    while (cur.isWithinLimits(i)) {
      char c = cur.charAt(i);
      if (c == SPACE) {
        cur.failAt(i, "No spaces allowed in property names.");
      } else if (c == PERIOD || c == OPEN_BRACKET) {
        end = i;
        break;
      } else if (c == OPEN_BRACE) {
        cur.failAt(i, "Expressions are not supported.");
      }
      i++;
    }
    if (end == 0) {
      end = cur.len();
    }

    cur.setPos(end);

    String property = cur.subSequence(start, end).toString();
    builder.name(property);
  }

  private static boolean tryToParseWildcard(Cursor cur, JsonPath.Builder builder)
      throws ParseError {
    if (!cur.nextIsIgnoreSpaces(cur.pos, ASTERISK)) {
      return false;
    }
    int asteriskPos = cur.findCharSkipSpaces(cur.pos, ASTERISK);
    if (!cur.nextIsIgnoreSpaces(asteriskPos, CLOSE_BRACKET)) {
      int offset = asteriskPos + 1;
      cur.failAt(offset, "Expected '" + CLOSE_BRACKET + "'");
    }
    int closedAt = cur.findCharSkipSpaces(asteriskPos, CLOSE_BRACKET);
    cur.setPos(closedAt + 1);
    builder.anyChild();
    return true;
  }

  private static boolean tryToParseIndex(Cursor cur, JsonPath.Builder builder) throws ParseError {
    int start = cur.position() + 1;
    int end = cur.indexOf(start, CLOSE_BRACKET);

    if (end < 0) {
      return false;
    }

    String expr = cur.subSequence(start, end).toString().trim();

    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      if (!isDigit(c)) {
        return false;
      }
    }

    try {
      int index = Integer.parseInt(expr);
      builder.index(index);
    } catch (NumberFormatException ex) {
      cur.fail("Invalid array index. Must be an integer.");
    }

    cur.setPos(end + 1);

    return true;
  }

  private static boolean tryToParsePropertyInBrackets(Cursor cur, JsonPath.Builder builder)
      throws ParseError {
    char quote = cur.findNonSpace();
    if (quote != SINGLE_QUOTE && quote != DOUBLE_QUOTE) {
      return false;
    }

    String property = null;

    int startPosition = cur.position() + 1;
    int readPosition = startPosition;
    int endPosition = 0;
    boolean inProperty = false;

    while (cur.isWithinLimits(readPosition)) {
      char c = cur.charAt(readPosition);

      if (ESC == c) {
        cur.failAt(readPosition, "Escape character is not supported in property name.");
      } else if (c == COMMA) {
        String message =
            inProperty
                ? "Comma is not allowed in property name."
                : "Multiple properties are not supported.";
        cur.failAt(readPosition, message);
      } else if (c == CLOSE_BRACKET && !inProperty) {
        break;
      } else if (c == quote) {
        if (inProperty) {
          endPosition = readPosition;
          property = cur.subSequence(startPosition, endPosition).toString();
          inProperty = false;
        } else {
          startPosition = readPosition + 1;
          inProperty = true;
        }
      }
      readPosition++;
    }

    if (inProperty) {
      cur.fail("Property has not been closed - missing closing " + quote);
    }

    int endBracketIndex = cur.findCharSkipSpaces(endPosition, CLOSE_BRACKET);
    if (endBracketIndex < 0) {
      cur.fail("Property has not been closed - missing closing '" + CLOSE_BRACKET + "'");
    }
    endBracketIndex++;

    cur.setPos(endBracketIndex);

    builder.name(property);
    return true;
  }

  private static class Cursor {
    private final CharSequence data;
    private int pos;
    private final int endPos;

    public Cursor(CharSequence data) {
      this.data = data;
      this.pos = 0;
      this.endPos = data.length() - 1;
    }

    public int len() {
      return endPos + 1;
    }

    public char charAt(int idx) {
      return data.charAt(idx);
    }

    public boolean is(char c) {
      return data.charAt(pos) == c;
    }

    public boolean nextIs(char c) {
      return isWithinLimits(pos + 1) && (data.charAt(pos + 1) == c);
    }

    public void incPos(int count) {
      pos += count;
    }

    public void setPos(int newPos) {
      pos = newPos;
    }

    public int position() {
      return pos;
    }

    public int findCharSkipSpaces(int startAt, char c) {
      int p = startAt == endPos ? startAt : skipSpaces(startAt + 1);
      if (charAt(p) == c) {
        return p;
      } else {
        return -1;
      }
    }

    private int skipSpaces(int p) {
      while (isWithinLimits(p + 1) && charAt(p) == SPACE) {
        p++;
      }
      return p;
    }

    public int indexOf(int start, char c) {
      int p = start;
      while (isWithinLimits(p)) {
        if (charAt(p) == c) {
          return p;
        }
        p++;
      }
      return -1;
    }

    public boolean nextIsIgnoreSpaces(int start, char c) {
      int p = skipSpaces(start + 1);
      return isWithinLimits(p) && charAt(p) == c;
    }

    public char findNonSpace() {
      return findNonSpace(pos);
    }

    public char findNonSpace(int start) {
      int p = skipSpaces(start + 1);
      if (isWithinLimits(p)) {
        return charAt(p);
      } else {
        return SPACE;
      }
    }

    private boolean isWithinLimits() {
      return pos <= endPos;
    }

    public boolean hasMoreCharacters() {
      return isWithinLimits(pos + 1);
    }

    public boolean isWithinLimits(int p) {
      return p >= 0 && p <= endPos;
    }

    public CharSequence subSequence(int start, int end) {
      return data.subSequence(start, end);
    }

    private void fail(String message) throws ParseError {
      failAt(this.pos, message);
    }

    private void failAt(int position, String message) throws ParseError {
      throw new ParseError(this.data, position, message);
    }
  }
}
