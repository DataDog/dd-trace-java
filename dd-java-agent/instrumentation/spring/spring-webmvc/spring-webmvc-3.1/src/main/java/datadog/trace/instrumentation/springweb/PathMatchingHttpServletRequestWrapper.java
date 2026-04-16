package datadog.trace.instrumentation.springweb;

import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

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
