package datadog.trace.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits a {@code multipart/*} body into its parts and reads {@code Content-Type} / {@code
 * Content-Disposition} parameters.
 */
final class MultipartSplitter {

  private static final int MAX_BOUNDARY_LENGTH = 70;

  private MultipartSplitter() {}

  /**
   * One part of a multipart body. Content is an index range into the body passed to {@link #split}.
   */
  static final class Part {
    private final Map<String, String> headers;
    final int contentStart;
    final int contentEnd;

    private Part(final Map<String, String> headers, final int contentStart, final int contentEnd) {
      this.headers = headers;
      this.contentStart = contentStart;
      this.contentEnd = contentEnd;
    }

    /**
     * @param lowercaseName the header name, lowercased by the caller
     */
    String header(final String lowercaseName) {
      return headers.get(lowercaseName);
    }
  }

  /**
   * Splits a multipart body into at most {@code partBudget} parts.
   *
   * @return the parts found, in order; empty if the body holds none
   */
  static List<Part> split(final String body, final String boundary, final int partBudget) {
    final List<Part> parts = new ArrayList<>();
    if (body == null || boundary == null || boundary.isEmpty() || partBudget <= 0) {
      return parts;
    }
    final String delimiter = "--" + boundary;
    final int length = body.length();
    int position = body.startsWith(delimiter) ? 0 : nextDelimiter(body, delimiter, 0);

    while (position >= 0 && parts.size() < partBudget) {
      final int afterDelimiter = position + delimiter.length();
      if (body.startsWith("--", afterDelimiter)) {
        // Close delimiter: anything past it is the epilogue
        break;
      }
      final int headerStart = lineStart(body, afterDelimiter);
      if (headerStart < 0) {
        // Not a part boundary after all — the delimiter was part of some part's content
        position = nextDelimiter(body, delimiter, afterDelimiter);
        continue;
      }
      final Map<String, String> headers = new HashMap<>(4);
      int cursor = headerStart;
      boolean headersComplete = false;
      int malformedPartEnd = -1;
      while (cursor < length) {
        if (body.startsWith(delimiter, cursor)) {
          // This part's headers are not followed by a blank line. Stop here: reading on would
          // consume the next part's delimiter and merge its headers into this part, collapsing
          // every following part into this one.
          malformedPartEnd = cursor;
          break;
        }
        final int newline = body.indexOf('\n', cursor);
        final int lineEnd = newline < 0 ? length : newline;
        final int trimmed =
            lineEnd > cursor && body.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
        if (trimmed == cursor) {
          headersComplete = true;
          cursor = newline < 0 ? length : newline + 1;
          break;
        }
        addHeader(headers, body, cursor, trimmed);
        if (newline < 0) {
          cursor = length;
          break;
        }
        cursor = newline + 1;
      }
      if (malformedPartEnd >= 0) {
        // Resume at the delimiter the headers ran into. It is past the current position, so the
        // outer scan still makes progress.
        position = malformedPartEnd;
        continue;
      }
      if (!headersComplete) {
        // Body truncated inside the headers: there is no content to report
        break;
      }
      final int next = nextDelimiter(body, delimiter, cursor);
      parts.add(new Part(headers, cursor, contentEnd(body, cursor, next)));
      position = next;
    }
    return parts;
  }

  /**
   * Reads the {@code boundary} parameter of a {@code Content-Type} header, case preserved.
   *
   * @return the boundary, or {@code null} if absent or unusable
   */
  static String extractBoundary(final String contentType) {
    final String boundary = parameter(contentType, "boundary");
    if (boundary == null || boundary.isEmpty() || boundary.length() > MAX_BOUNDARY_LENGTH) {
      return null;
    }
    return boundary;
  }

  /**
   * Reads a header parameter, case preserved. The parameter name is matched case-insensitively and
   * only at a parameter boundary, so {@code filename} does not satisfy a lookup for {@code name}.
   * Quoted values may contain separators and {@code \"} escapes, and are skipped whole: a field
   * named {@code "; filename=x"} does not read as a {@code filename} parameter. RFC 7230 optional
   * whitespace is tolerated on both sides of the {@code =}.
   *
   * @return the parameter value, {@code ""} when it is present but empty, or {@code null} when the
   *     parameter is absent
   */
  static String parameter(final String headerValue, final String paramName) {
    if (headerValue == null || paramName == null) {
      return null;
    }
    final int length = headerValue.length();
    final int nameLength = paramName.length();
    boolean atBoundary = true;
    for (int i = 0; i < length; i++) {
      final char c = headerValue.charAt(i);
      if (c == '"') {
        i = endOfQuoted(headerValue, i);
        atBoundary = false;
        continue;
      }
      if (atBoundary && headerValue.regionMatches(true, i, paramName, 0, nameLength)) {
        final int equals = skipOptionalWhitespace(headerValue, i + nameLength);
        if (equals < length && headerValue.charAt(equals) == '=') {
          return value(headerValue, skipOptionalWhitespace(headerValue, equals + 1));
        }
      }
      atBoundary = isParameterSeparator(c);
    }
    return null;
  }

  /**
   * Walks a quoted value, honouring {@code \"} escapes.
   *
   * @param openQuote the index of the opening quote
   * @return the index of the closing quote, or the body length when the quote is unterminated
   */
  private static int endOfQuoted(final String headerValue, final int openQuote) {
    final int length = headerValue.length();
    for (int i = openQuote + 1; i < length; i++) {
      final char c = headerValue.charAt(i);
      if (c == '\\' && i + 1 < length) {
        i++;
      } else if (c == '"') {
        return i;
      }
    }
    return length;
  }

  private static String value(final String headerValue, final int from) {
    final int length = headerValue.length();
    if (from < length && headerValue.charAt(from) == '"') {
      // An unterminated quote ends at the body length, so what was read is kept rather than the
      // parameter being dropped.
      final int close = endOfQuoted(headerValue, from);
      final StringBuilder unquoted = new StringBuilder(close - from);
      for (int i = from + 1; i < close; i++) {
        final char c = headerValue.charAt(i);
        if (c == '\\' && i + 1 < length) {
          unquoted.append(headerValue.charAt(++i));
        } else {
          unquoted.append(c);
        }
      }
      return unquoted.toString();
    }
    int end = length;
    for (int i = from; i < length; i++) {
      if (isParameterSeparator(headerValue.charAt(i))) {
        end = i;
        break;
      }
    }
    return headerValue.substring(from, end);
  }

  private static int skipOptionalWhitespace(final String headerValue, final int from) {
    int i = from;
    final int length = headerValue.length();
    while (i < length && (headerValue.charAt(i) == ' ' || headerValue.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }

  private static boolean isParameterSeparator(final char c) {
    return c == ';' || c == ',' || c == ' ' || c == '\t';
  }

  /**
   * Finds the next delimiter at or after {@code from}.
   *
   * @return the index the delimiter starts at, or {@code -1} if there is none
   */
  private static int nextDelimiter(final String body, final String delimiter, final int from) {
    if (body.startsWith(delimiter, from)) {
      return from;
    }
    for (int newline = body.indexOf('\n', from);
        newline >= 0;
        newline = body.indexOf('\n', newline + 1)) {
      if (body.startsWith(delimiter, newline + 1)) {
        return newline + 1;
      }
    }
    return -1;
  }

  /**
   * Skips the linear whitespace and line break that follow a delimiter.
   *
   * @return the index the header lines start at, or {@code -1} if no line break follows
   */
  private static int lineStart(final String body, final int from) {
    int i = from;
    final int length = body.length();
    while (i < length && (body.charAt(i) == ' ' || body.charAt(i) == '\t')) {
      i++;
    }
    if (i < length && body.charAt(i) == '\r') {
      i++;
    }
    return i < length && body.charAt(i) == '\n' ? i + 1 : -1;
  }

  /**
   * Ends a part's content before the line break that introduces the next delimiter, or at the end
   * of the body when the closing delimiter was truncated away.
   */
  private static int contentEnd(final String body, final int contentStart, final int next) {
    if (next < 0) {
      return body.length();
    }
    int end = next > contentStart ? next - 1 : contentStart;
    if (end > contentStart && body.charAt(end - 1) == '\r') {
      end--;
    }
    return end;
  }

  private static void addHeader(
      final Map<String, String> headers, final String body, final int from, final int to) {
    for (int i = from; i < to; i++) {
      if (body.charAt(i) == ':') {
        final String name = body.substring(from, i).trim().toLowerCase(Locale.ROOT);
        if (!name.isEmpty()) {
          headers.put(name, body.substring(i + 1, to).trim());
        }
        return;
      }
    }
    // No colon: not a header line, and no obs-fold support, so drop it
  }
}
