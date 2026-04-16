package datadog.trace.instrumentation.springweb6;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.HashMap;
import java.util.Map;

class PathMatchingHttpServletRequestWrapper extends HttpServletRequestWrapper {
  private Map<String, Object> localAttributes;

  public PathMatchingHttpServletRequestWrapper(HttpServletRequest request) {
    super(request);
  }

  @Override
  public Object getAttribute(String name) {
    if (localAttributes != null) {
      final Object ret = localAttributes.get(name);
      if (ret != null) {
        return ret;
      }
    }
    return super.getAttribute(name);
  }

  @Override
  public void setAttribute(String name, Object o) {
    if (localAttributes == null) {
      localAttributes = new HashMap<>();
    }
    localAttributes.put(name, o);
  }

  @Override
  public void removeAttribute(String name) {
    if (localAttributes != null) {
      localAttributes.remove(name);
    }
  }
}
