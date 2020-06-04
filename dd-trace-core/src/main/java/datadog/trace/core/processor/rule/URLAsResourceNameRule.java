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
        int start = url.indexOf("://");
        final boolean hasProtocol = start >= 0;
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
    for (int i = start; i < url.length(); i = end) {
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
}
