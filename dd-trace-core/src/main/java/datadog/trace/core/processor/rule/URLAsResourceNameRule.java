package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;

public class URLAsResourceNameRule implements TraceProcessor.Rule {

  private static final BitSlicedBitapSearch PROTOCOL_SEARCH = new BitSlicedBitapSearch("://");

  @Override
  public String[] aliases() {
    return new String[] {"URLAsResourceName", "Status404Rule", "Status404Decorator"};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    if (span.isResourceNameSet()) {
      return;
    }
    final Object httpStatus = span.getTag(Tags.HTTP_STATUS);
    if (null != httpStatus && (httpStatus.equals(404) || "404".equals(httpStatus))) {
      span.setResourceName("404");
      return;
    }
    final Object url = span.getTag(Tags.HTTP_URL);
    if (null == url) {
      return;
    }
    span.setResourceName(extractResourceNameFromURL(span.getTag(Tags.HTTP_METHOD), url.toString()));
  }

  private String extractResourceNameFromURL(final Object method, final String url) {
    if (url.isEmpty()) {
      return null == method ? "/" : method.toString().toUpperCase().trim() + " /";
    } else {
      StringBuilder resourceName = new StringBuilder(16);
      if (method != null) {
        final String verb = method.toString().toUpperCase().trim();
        resourceName.append(verb).append(' ');
      }
      // skip the protocol info if present
      int start = protocolPosition(url);
      boolean hasProtocol = start >= 0;
      start += hasProtocol ? 3 : 1;
      if (hasProtocol) { // then we need to terminate when an ? or # is found
        start = url.indexOf('/', start);
        if (start == -1) { // then this is just a hostname
          resourceName.append('/');
        } else { // ignore the hostname and remove any high cardinality info
          cleanResourceName(url, resourceName, start);
        }
      } else { // just need to remove any high cardinality info
        cleanResourceName(url, resourceName, start);
      }
      return resourceName.toString();
    }
  }

  private void cleanResourceName(String url, StringBuilder resourceName, int start) {
    boolean lastSegment = false;
    int segmentEnd;
    for (int i = start; i < url.length() && !lastSegment; i = segmentEnd) {
      if (url.charAt(i) == '/') { // always keep forward slashes
        resourceName.append('/');
        ++i;
      }
      // find the end of the current section as quickly as possible,
      // meaning we process each character at most twice. Even if
      // we do, each segment is expected to be short, say, at most
      // 50 characters
      segmentEnd = url.indexOf('/', i);
      if (segmentEnd == -1) {
        // according to https://tools.ietf.org/html/rfc3986#section-3
        // fragments come after query parts, so ? should be closer than #
        segmentEnd = url.indexOf('?', i);
        if (segmentEnd == -1) {
          segmentEnd = url.indexOf('#', i);
          if (segmentEnd == -1) {
            segmentEnd = url.length();
          }
        }
        lastSegment = true;
      }
      if (i < segmentEnd) {
        // now check if what's in the current section should be scrubbed or
        // appended to the output
        int snapshot = resourceName.length();
        char c = url.charAt(i);
        resourceName.append(c);
        // versions can start with v, V, up to 2 numbers, and can't be the last segment
        // in the URL
        boolean isVersion = !lastSegment & (c == 'v' | c == 'V') & (segmentEnd - i) <= 3;
        // if we find numeric characters which aren't part of a version, the segment will
        // be removed
        boolean containsNumerics = Character.isDigit(c);
        boolean isBlank = Character.isWhitespace(c);
        // most of the time, will get out of this loop quickly,
        // except when accumulating characters we need to keep
        for (int j = i + 1; j < segmentEnd && (!containsNumerics || isVersion || isBlank); ++j) {
          c = url.charAt(j);
          isVersion &= Character.isDigit(c);
          containsNumerics |= Character.isDigit(c);
          isBlank &= Character.isWhitespace(c);
          resourceName.append(c); // append speculatively
        }
        // check if this section should be ignored
        if (containsNumerics && !isVersion) {
          resourceName.setLength(snapshot);
          resourceName.append('?');
        } else if (isBlank) {
          resourceName.setLength(snapshot);
        }
      }
    }
    if (resourceName.length() == 0) {
      resourceName.append('/');
    }
  }

  /**
   * This class does substring search on latin 1 strings of up to 32 characters, and will inspect
   * each character at most once in the input.
   *
   * <p>This class uses the bitap algorithm (https://en.wikipedia.org/wiki/Bitap_algorithm) but
   * adapted slightly in bit slicing the masks into high and low nibbles, which reduces spatial
   * overhead by a factor of 8.
   *
   * <p>This is only implemented because it's a compact, efficient, and easy to implement string
   * search algorithm, and the JDK's String.indexOf(String) doesn't allow specification of a limit.
   * This class allows searching within a range of the string.
   */
  private static class BitSlicedBitapSearch {
    private final int[] high;
    private final int[] low;
    private final int termination;

    BitSlicedBitapSearch(String term) {
      if (term.length() > 32) {
        throw new IllegalArgumentException("term must be shorter than 32 characters");
      }
      // these arrays each index the position of the character
      // by nibble. So if we have a character 'a' = 0b01100001
      // at position 0, we mark the first bit in high[0b0110]
      // and the first bit in low[0b1]. During matching, these
      // masks are intersected, so if get character 'b' = 0b01100010,
      // we match the high mask but not the low and disregard
      // the input.
      this.high = new int[16];
      this.low = new int[16];
      int mask = 1;
      for (char c : term.toCharArray()) {
        if (c >= 256) {
          throw new IllegalStateException("term must be latin 1");
        }
        low[c & 0xF] |= mask;
        high[(c >>> 4) & 0xF] |= mask;
        mask <<= 1;
      }
      this.termination = 1 << (term.length() - 1);
    }

    public int indexOf(String text, int from, int to) {
      int state = 0;
      to = Math.min(to, text.length());
      for (int i = from; i < to; ++i) {
        char c = text.charAt(i);
        if (c >= 256) { // oops, not latin 1 input
          state = 0;
        } else {
          int highMask = high[(c >>> 4) & 0xF];
          int lowMask = low[c & 0xF];
          state = ((state << 1) | 1) & highMask & lowMask;
          if ((state & termination) == termination) {
            return i - Long.numberOfTrailingZeros(termination);
          }
        }
      }
      return -1;
    }
  }

  private static int protocolPosition(String url) {
    // this is virtually always https or http, but there
    // may be protocols we don't know about so can't just
    // do url.startsWith("https://"), but they should
    // at least always be a few characters long. Stopping
    // the search at 16 avoids searching the entire string
    // when there is no protocol information.
    return PROTOCOL_SEARCH.indexOf(url, 0, 16);
  }
}
