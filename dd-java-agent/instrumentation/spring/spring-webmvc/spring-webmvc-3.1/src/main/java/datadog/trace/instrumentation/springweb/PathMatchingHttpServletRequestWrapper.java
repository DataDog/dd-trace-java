package datadog.trace.instrumentation.springweb;

import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

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
