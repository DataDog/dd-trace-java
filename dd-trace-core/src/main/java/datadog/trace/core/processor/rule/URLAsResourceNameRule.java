package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.processor.TraceProcessor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class URLAsResourceNameRule implements TraceProcessor.Rule {

  private static final Set<? extends Object> NOT_FOUND =
      new HashSet<>(Arrays.asList(Integer.valueOf(404), "404"));

  private static final BitSlicedBYG PROTOCOL_SEARCH = new BitSlicedBYG("://");

  private final ThreadLocal<StringBuilder> resourceNameBuilder =
      new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
          return new StringBuilder(100);
        }
      };

  @Override
  public String[] aliases() {
    return new String[] {"URLAsResourceName"};
  }

  @Override
  public void processSpan(final DDSpan span) {
    final DDSpanContext context = span.context();
    if (context.isResourceNameSet()) {
      return;
    }
    final Object httpStatus = span.getTag(Tags.HTTP_STATUS);
    if (NOT_FOUND.contains(httpStatus)) {
      span.setResourceName("404");
      return;
    }
    final Object url = span.getTag(Tags.HTTP_URL);
    if (null == url) {
      return;
    }
    context.setResourceName(
        extractResourceNameFromURL(span.getTag(Tags.HTTP_METHOD), url.toString()));
  }

  private String extractResourceNameFromURL(final Object method, final String url) {
    StringBuilder resourceName = resourceNameBuilder.get();
    try {
      if (method != null) {
        final String verb = method.toString().toUpperCase().trim();
        resourceName.append(verb).append(' ');
      }
      if (url.isEmpty()) {
        resourceName.append('/');
      } else {
        // skip the protocol info if present
        int start = protocolPosition(url);
        boolean hasProtocol = start >= 0;
        start += hasProtocol ? 3 : 1;
        if (hasProtocol) { // then we need to terminate when an ? or # is found
          start = url.indexOf('/', start);
          if (start == -1) {
            resourceName.append('/');
          } else { // need to scrub out sensitive info
            cleanResourceName(url, resourceName, start);
          }
        } else { // just need to scrub out sensitive looking info
          cleanResourceName(url, resourceName, start);
        }
      }
      return resourceName.toString();
    } finally {
      resourceName.setLength(0);
    }
  }

  private void cleanResourceName(String url, StringBuilder resourceName, int start) {
    boolean first = true;
    boolean last = false;
    int end = 0;
    for (int i = start; i < url.length() && !last; i = end) {
      if (url.charAt(i) == '/' || first) {
        resourceName.append('/');
        ++i;
      }
      end = url.indexOf('/', i);
      if (end == -1) {
        end = Math.max(url.indexOf('?', i), url.indexOf('#', i));
        if (end == -1) {
          end = url.length();
        }
        last = true;
      }
      if (i < end) {
        char c = url.charAt(i);
        boolean isVersion = !last & (c == 'v' | c == 'V') & (end - i) <= 3;
        boolean containsNumerics = Character.isDigit(c);
        boolean isBlank = Character.isWhitespace(c);
        for (int j = i + 1; j < end && (!containsNumerics || isVersion || isBlank); ++j) {
          c = url.charAt(j);
          isVersion &= Character.isDigit(c);
          containsNumerics |= Character.isDigit(c);
          isBlank &= Character.isWhitespace(c);
        }

        if (containsNumerics && !isVersion) {
          resourceName.append('?');
        } else if (!isBlank) {
          resourceName.append(url, i, end);
        }
        first = false;
      }
    }
  }

  private static class BitSlicedBYG {
    private final int[] high;
    private final int[] low;
    private final int termination;

    BitSlicedBYG(String term) {
      if (term.length() > 32) {
        throw new IllegalArgumentException("term must be shorter than 32 characters");
      }
      this.high = new int[16];
      this.low = new int[16];
      int termination = 1;
      for (char c : term.toCharArray()) {
        if (c >= 256) {
          throw new IllegalStateException("term must be latin 1");
        }
        low[c & 0xF] |= termination;
        high[(c >>> 4) & 0xF] |= termination;
        termination <<= 1;
      }
      this.termination = 1 << (term.length() - 1);
    }

    public int find(String text, int from, int to) {
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
    return PROTOCOL_SEARCH.find(url, 0, 16);
  }
}
