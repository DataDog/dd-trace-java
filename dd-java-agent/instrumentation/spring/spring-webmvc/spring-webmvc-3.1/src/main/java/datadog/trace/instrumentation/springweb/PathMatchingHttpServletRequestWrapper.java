package datadog.trace.instrumentation.springweb;

import static datadog.context.Context.root;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.springframework.web.servlet.HandlerMapping;

class PathMatchingHttpServletRequestWrapper extends HttpServletRequestWrapper {

  private final AgentSpan requestSpan;
  private boolean applied = false;

  public PathMatchingHttpServletRequestWrapper(HttpServletRequest request, AgentSpan requestSpan) {
    super(request);
    this.requestSpan = requestSpan;
  }

  @Override
  public void setAttribute(String name, Object o) {
    super.setAttribute(name, o);
    try {
      if (!applied && o != null && HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE.equals(name)) {
        applied = true;
        DECORATE.onRequest(requestSpan, this, this, root());
      }
    } catch (Throwable ignored) {
    }
  }
}
