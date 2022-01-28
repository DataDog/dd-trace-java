package datadog.trace.instrumentation.springweb;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.springframework.web.servlet.HandlerMapping;

class PathMatchingHttpServletRequestWrapper extends HttpServletRequestWrapper {

  private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";
  private static final String MATRIX_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.matrixVariables";

  private final boolean appSecEnabled;
  private Object bestMatchingPattern;
  Map<String, Object> templateParams;
  Map<String, Object> matrixParams;

  public PathMatchingHttpServletRequestWrapper(HttpServletRequest request, boolean appSecEnabled) {
    super(request);
    this.appSecEnabled = appSecEnabled;
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
    } else if (appSecEnabled && o instanceof Map) {
      if (name.equals(URI_TEMPLATE_VARIABLES_ATTRIBUTE)) {
        this.templateParams = (Map<String, Object>) o;
      } else if (name.equals(MATRIX_VARIABLES_ATTRIBUTE)) {
        this.matrixParams = (Map<String, Object>) o;
      }
    }
  }

  @Override
  public void removeAttribute(String name) {
    if (name.equals(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)) {
      bestMatchingPattern = null;
    }
  }
}
