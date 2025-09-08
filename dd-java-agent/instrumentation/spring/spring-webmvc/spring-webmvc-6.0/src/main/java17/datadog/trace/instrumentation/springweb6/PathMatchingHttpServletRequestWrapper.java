package datadog.trace.instrumentation.springweb6;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.HashMap;
import java.util.Map;

class PathMatchingHttpServletRequestWrapper extends HttpServletRequestWrapper {
  private final Map<String, Object> localAttributes = new HashMap<>();

  public PathMatchingHttpServletRequestWrapper(HttpServletRequest request) {
    super(request);
  }

  @Override
  public Object getAttribute(String name) {
    final Object ret = localAttributes.get(name);
    if (ret == null) {
      return super.getAttribute(name);
    }
    return ret;
  }

  @Override
  public void setAttribute(String name, Object o) {
    localAttributes.put(name, o);
  }

  @Override
  public void removeAttribute(String name) {
    localAttributes.remove(name);
  }
}
