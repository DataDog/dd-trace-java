package datadog.trace.instrumentation.springweb;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

public class HandlerMappingResourceNameFilter extends OncePerRequestFilter implements Ordered {

  private static final Logger log = LoggerFactory.getLogger(HandlerMappingResourceNameFilter.class);
  private final List<HandlerMapping> handlerMappings = new CopyOnWriteArrayList<>();

  private boolean appSecEnabled;

  public void setAppSecEnabled(boolean appSecEnabled) {
    this.appSecEnabled = appSecEnabled;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (parentSpan instanceof AgentSpan) {
      PathMatchingHttpServletRequestWrapper wrappedRequest =
          new PathMatchingHttpServletRequestWrapper(request, appSecEnabled);
      try {
        if (findMapping(wrappedRequest)) {
          // Name the parent span based on the matching pattern
          // Let the parent span resource name be set with the attribute set in findMapping.
          DECORATE.onRequest((AgentSpan) parentSpan, wrappedRequest, wrappedRequest, null);
        }
      } catch (final Exception ignored) {
        // mapping.getHandler() threw exception.  Ignore
      }

      if (appSecEnabled) {
        appSecPathParamProcess(
            (AgentSpan) parentSpan, wrappedRequest.templateParams, wrappedRequest.matrixParams);
      }
    }

    filterChain.doFilter(request, response);
  }

  private void appSecPathParamProcess(
      AgentSpan agentSpan, Map<String, Object> templateParams, Map<String, Object> matrixParams) {
    Map<String, Object> map = templateParams;

    if (matrixParams != null) {
      map = new HashMap<>(map);
      for (Map.Entry<String, Object> e : matrixParams.entrySet()) {
        String key = e.getKey();
        Object curValue = map.get(key);
        if (curValue != null) {
          map.put(key, new PairList(curValue, e.getValue()));
        } else {
          map.put(key, e.getValue());
        }
      }
    }

    if (map != null) {
      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Map<String, Object>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      callback.apply(requestContext, map);
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

    public BeanDefinition(boolean appSecEnabled) {
      super(HandlerMappingResourceNameFilter.class);
      setBeanClassName(HandlerMappingResourceNameFilter.class.getName());
      setScope(SCOPE_SINGLETON);
      if (appSecEnabled) {
        MutablePropertyValues propValues = new MutablePropertyValues();
        propValues.add("appSecEnabled", Boolean.TRUE);
        setPropertyValues(propValues);
      }
    }
  }
}
