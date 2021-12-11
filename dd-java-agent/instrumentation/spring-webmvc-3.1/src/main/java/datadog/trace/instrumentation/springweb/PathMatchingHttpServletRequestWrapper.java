package datadog.trace.instrumentation.springweb;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.springframework.web.servlet.HandlerMapping;

final class PathMatchingHttpServletRequestWrapper extends HttpServletRequestWrapper {
  private Object bestMatchingPattern;

  public PathMatchingHttpServletRequestWrapper(HttpServletRequest request) {
    super(request);
  }

  @Override
  public Object getAttribute(String name) {
    if (name.equals(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)) {
      return bestMatchingPattern;
    }
    return super.getAttribute(name);
  }

  @Override
  public void setAttribute(String name, Object o) {
    if (name.equals(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)) {
      bestMatchingPattern = o;
    }
  }

  @Override
  public void removeAttribute(String name) {
    if (name.equals(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)) {
      bestMatchingPattern = null;
    }
  }
}
