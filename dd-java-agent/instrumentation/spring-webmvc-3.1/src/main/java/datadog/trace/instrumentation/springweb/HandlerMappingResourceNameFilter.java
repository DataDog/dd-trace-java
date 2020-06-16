package datadog.trace.instrumentation.springweb;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.List;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

public class HandlerMappingResourceNameFilter extends OncePerRequestFilter implements Ordered {
  private volatile List<HandlerMapping> handlerMappings;

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);

    if (handlerMappings != null && parentSpan instanceof AgentSpan) {
      try {
        if (findMapping(request)) {
          // Name the parent span based on the matching pattern
          // Let the parent span resource name be set with the attribute set in findMapping.
          DECORATE.onRequest((AgentSpan) parentSpan, request);
        }
      } catch (final Exception ignored) {
        // mapping.getHandler() threw exception.  Ignore
      }

      filterChain.doFilter(request, response);
    }
  }

  /**
   * When a HandlerMapping matches a request, it sets HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
   * as an attribute on the request. This attribute is read by
   * SpringWebHttpServerDecorator.onRequest and set as the resource name.
   */
  private boolean findMapping(final HttpServletRequest request) throws Exception {
    for (final HandlerMapping mapping : handlerMappings) {
      final HandlerExecutionChain handler = mapping.getHandler(request);
      if (handler != null) {
        return true;
      }
    }
    return false;
  }

  public void setHandlerMappings(final List<HandlerMapping> handlerMappings) {
    this.handlerMappings = handlerMappings;
  }

  @Override
  public int getOrder() {
    // Run after all HIGHEST_PRECEDENCE items
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }
}
