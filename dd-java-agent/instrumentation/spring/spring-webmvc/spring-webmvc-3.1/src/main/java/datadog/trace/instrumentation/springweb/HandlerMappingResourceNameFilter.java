package datadog.trace.instrumentation.springweb;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

public class HandlerMappingResourceNameFilter extends OncePerRequestFilter implements Ordered {

  private static final Logger log = LoggerFactory.getLogger(HandlerMappingResourceNameFilter.class);
  private final List<HandlerMapping> handlerMappings = new CopyOnWriteArrayList<>();

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextObj instanceof Context) {
      Context context = (Context) contextObj;
      AgentSpan parentSpan = spanFromContext(context);
      if (parentSpan != null) {
        PathMatchingHttpServletRequestWrapper wrappedRequest =
            new PathMatchingHttpServletRequestWrapper(request);
        try {
          if (findMapping(wrappedRequest)) {
            // Name the parent span based on the matching pattern
            // Let the parent span resource name be set with the attribute set in findMapping.
            DECORATE.onRequest(parentSpan, wrappedRequest, wrappedRequest, root());
          }
        } catch (final Exception ignored) {
          // mapping.getHandler() threw exception.  Ignore
        }
      }
    }

    filterChain.doFilter(request, response);
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
    for (HandlerMapping handlerMapping : handlerMappings) {
      if (handlerMapping instanceof RequestMappingInfoHandlerMapping) {
        if (!this.handlerMappings.contains(handlerMapping)) {
          this.handlerMappings.add(handlerMapping);
        }
      } else {
        log.debug(
            "discarding handler mapping {} which won't set BEST_MATCHING_PATTERN_ATTRIBUTE",
            handlerMapping);
      }
    }
  }

  @Override
  public int getOrder() {
    // Run after all HIGHEST_PRECEDENCE items
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  public static class BeanDefinition extends AnnotatedGenericBeanDefinition {
    private static final long serialVersionUID = 5623859691503032280L;

    public BeanDefinition() {
      super(HandlerMappingResourceNameFilter.class);
      // don't call setBeanClassName as it overwrites 'beanClass'
      setScope(SCOPE_SINGLETON);
      setLazyInit(true);
    }
  }
}
