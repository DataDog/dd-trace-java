package datadog.trace.lambda;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    /** {@code null} when the part declares no such header. Duplicates keep the last seen value. */
    final String contentDisposition;

    final String contentType;
    final int contentStart;
    final int contentEnd;

    private Part(
        final String contentDisposition,
        final String contentType,
        final int contentStart,
        final int contentEnd) {
      this.contentDisposition = contentDisposition;
      this.contentType = contentType;
      this.contentStart = contentStart;
      this.contentEnd = contentEnd;
    }
  }

  /**
   * Splits a multipart body into at most {@code partBudget} parts.
   *
   * @return the parts found, in order; empty if the body holds none or is not parseable as
   *     multipart
   */
  static List<Part> split(final String body, final String boundary, final int partBudget) {
    final List<Part> parts = new ArrayList<>();
    if (body == null || boundary == null || boundary.isEmpty() || partBudget <= 0) {
      return parts;
    }
    final String delimiter = "--" + boundary;
    final int length = body.length();
    int position = nextDelimiter(body, delimiter, 0);

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
      // Only these two headers are ever read, so the others are matched and dropped rather than
      // collected: a part declaring thousands of them costs nothing but the scan.
      String contentDisposition = null;
      String contentType = null;
      int cursor = headerStart;
      boolean headersComplete = false;
      while (cursor < length) {
        if (body.startsWith(delimiter, cursor) && endsLine(body, cursor + delimiter.length())) {
          // This part's headers are not followed by a blank line, so no conforming parser reads
          // this body as multipart. Report nothing rather than the parts that happen to survive:
          // the caller then keeps the raw string, and the WAF sees every byte of it.
          return Collections.emptyList();
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
        final String disposition = headerValue(body, cursor, trimmed, "Content-Disposition");
        if (disposition != null) {
          contentDisposition = disposition;
        } else {
          final String type = headerValue(body, cursor, trimmed, "Content-Type");
          if (type != null) {
            contentType = type;
          }
        }
        if (newline < 0) {
          cursor = length;
          break;
        }
        cursor = newline + 1;
      }
      if (!headersComplete) {
        // The body ended inside this part's headers. They are content in their own right — a
        // filename lives there — and no parser accepts a body cut short like this, so report
        // nothing and let the caller keep the raw string.
        return Collections.emptyList();
      }
      // The search starts past the first line break of the content, never at the content itself: a
      // delimiter there would have no line break of its own, having spent the one that terminated
      // the headers.
      final int contentBreak = body.indexOf('\n', cursor);
      final int next = contentBreak < 0 ? -1 : nextDelimiter(body, delimiter, contentBreak + 1);
      parts.add(new Part(contentDisposition, contentType, cursor, contentEnd(body, cursor, next)));
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
   * Reads a header parameter, case preserved. The name is matched case-insensitively and only at a
   * parameter boundary, so {@code filename} does not satisfy a lookup for {@code name}. Quoted
   * values may hold separators and {@code \"} escapes, and are skipped whole: a field named {@code
   * "; filename=x"} does not read as a {@code filename} parameter.
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
    while (i < length && isOptionalWhitespace(headerValue.charAt(i))) {
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
    if (body.startsWith(delimiter, from) && endsLine(body, from + delimiter.length())) {
      return from;
    }
    for (int newline = body.indexOf('\n', from);
        newline >= 0;
        newline = body.indexOf('\n', newline + 1)) {
      if (body.startsWith(delimiter, newline + 1)
          && endsLine(body, newline + 1 + delimiter.length())) {
        return newline + 1;
      }
    }
    return -1;
  }

  /**
   * A delimiter only delimits if its line ends there, RFC 2046 transport padding aside — for the
   * close delimiter, past its trailing {@code --}. A content line that merely starts with one, be
   * it {@code --x-not-a-boundary} or {@code --x--not-a-close}, is data, and must not end the part
   * early and hide the rest from the WAF.
   *
   * @param after the index just past the matched delimiter
   */
  private static boolean endsLine(final String body, final int after) {
    final int end = body.startsWith("--", after) ? after + 2 : after;
    return end == body.length() || lineStart(body, end) >= 0;
  }

  /**
   * Skips the linear whitespace and line break that follow a delimiter.
   *
   * @return the index the header lines start at, or {@code -1} if no line break follows
   */
  private static int lineStart(final String body, final int from) {
    int i = from;
    final int length = body.length();
    while (i < length && isOptionalWhitespace(body.charAt(i))) {
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

  /**
   * Reads one header line, without allocating unless the name is the one asked for.
   *
   * @param from the first index of the line, {@code to} the index past its last character, the
   *     trailing {@code \r} already excluded
   * @param name the header name to match, case-insensitively
   * @return the trimmed header value, or {@code null} when the line declares another header or is
   *     not a header line at all — there is no colon, and obs-fold is unsupported
   */
  private static String headerValue(
      final String body, final int from, final int to, final String name) {
    int colon = -1;
    for (int i = from; i < to; i++) {
      if (body.charAt(i) == ':') {
        colon = i;
        break;
      }
    }
    if (colon < 0) {
      return null;
    }
    int start = from;
    int end = colon;
    while (start < end && isOptionalWhitespace(body.charAt(start))) {
      start++;
    }
    while (end > start && isOptionalWhitespace(body.charAt(end - 1))) {
      end--;
    }
    // The canonical spelling first: that comparison is intrinsified, and every real client sends it
    if (end - start != name.length()
        || !(body.regionMatches(start, name, 0, name.length())
            || body.regionMatches(true, start, name, 0, name.length()))) {
      return null;
    }
    return body.substring(colon + 1, to).trim();
  }

  private static boolean isOptionalWhitespace(final char c) {
    return c == ' ' || c == '\t';
  }
}
