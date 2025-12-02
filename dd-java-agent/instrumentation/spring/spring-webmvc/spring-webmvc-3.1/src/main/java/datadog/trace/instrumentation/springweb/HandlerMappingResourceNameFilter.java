package datadog.trace.instrumentation.springweb;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

public class HandlerMappingResourceNameFilter extends OncePerRequestFilter implements Ordered {

  private boolean hasPatternMatchers = true;

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final Object contextObj;
    HttpServletRequest requestToUse = request;
    if (hasPatternMatchers
        && (contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE)) instanceof Context) {
      Context context = (Context) contextObj;
      AgentSpan parentSpan = spanFromContext(context);
      if (parentSpan != null) {
        requestToUse = new PathMatchingHttpServletRequestWrapper(request, parentSpan);
      }
    }

    filterChain.doFilter(requestToUse, response);
  }

  public void setHasPatternMatchers(boolean hasPatternMatchers) {
    this.hasPatternMatchers = hasPatternMatchers;
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
