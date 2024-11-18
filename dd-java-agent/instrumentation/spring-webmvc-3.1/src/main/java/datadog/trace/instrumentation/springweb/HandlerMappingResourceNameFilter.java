package datadog.trace.instrumentation.springweb;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.Enumeration;
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
      HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (parentSpan instanceof AgentSpan) {
      PathMatchingHttpServletRequestWrapper wrappedRequest =
          new PathMatchingHttpServletRequestWrapper(request);
      try {
        if (findMapping(wrappedRequest)) {
          // Name the parent span based on the matching pattern
          // Let the parent span resource name be set with the attribute set in findMapping.
          DECORATE.onRequest((AgentSpan) parentSpan, wrappedRequest, wrappedRequest, null);
        }
      } catch (final Exception ignored) {
        // mapping.getHandler() threw exception.  Ignore
      }
      AgentSpan span = (AgentSpan) parentSpan;

      ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
      ContentCachingRequestWrapper requestWrapper = null;
      String contextType =null;
      String methodType = null;
      boolean tracerHeader = Config.get().isTracerHeaderEnabled();
      if (!(tracerHeader || Config.get().isTracerRequestBodyEnabled())) {
        filterChain.doFilter(request, responseWrapper);
      }else{
        requestWrapper = new ContentCachingRequestWrapper(request);
        filterChain.doFilter(requestWrapper, responseWrapper);
      }


      byte[] data = responseWrapper.getContentAsByteArray();
      responseWrapper.copyBodyToResponse();

      if (tracerHeader) {
        contextType = requestWrapper.getContentType();
        methodType = requestWrapper.getMethod();
        StringBuffer requestHeader = new StringBuffer("");
        StringBuffer responseHeader = new StringBuffer("");
        Enumeration<String> headerNames = requestWrapper.getHeaderNames();
        int count = 0;
        while (headerNames.hasMoreElements()) {
          if (count==0){
            requestHeader.append("{");
          }else{
            requestHeader.append(",\n");
          }
          String headerName = headerNames.nextElement();
          requestHeader.append("\"").append(headerName).append("\":").append("\"").append(requestWrapper.getHeader(headerName).replace("\"","")).append("\"");
          count ++;
        }
        if (count>0){
          requestHeader.append("}");
        }
        count = 0;
        for (String headerName : responseWrapper.getHeaderNames()) {
          if (count==0){
            responseHeader.append("{");
            responseHeader.append("\"guance_trace_id\":").append("\"").append(GlobalTracer.get().getTraceId()).append("\"");
          }else{
            responseHeader.append(",\n");
          }
          responseHeader.append("\"").append(headerName).append("\":").append("\"").append(responseWrapper.getHeader(headerName)).append("\"");
          count ++;
        }

        if (count>0){
          responseHeader.append("}");
        }
        span.setTag("request_header",requestHeader.toString());
        span.setTag("response_header",responseHeader.toString());
      }
      if (Config.get().isTracerRequestBodyEnabled() && "POST".equalsIgnoreCase(methodType) && contextType != null && (contextType.contains("application/json"))) {
        span.setTag("request_body", new String(requestWrapper.getContentAsByteArray()));
      }
      log.debug("response.getContentType() >>>> :{},traceId:{},responseBodyEnabled:{}",responseWrapper.getContentType(),GlobalTracer.get().getTraceId(),Config.get().isTracerResponseBodyEnabled());
      if (Config.get().isTracerResponseBodyEnabled()) {
        if (responseWrapper.getContentType() != null && (responseWrapper.getContentType().contains("application/json") || responseWrapper.getContentType().contains("text/plain"))) {
          span.setTag("response_body", new String(data));
        }
      }
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

    public BeanDefinition() {
      super(HandlerMappingResourceNameFilter.class);
      // don't call setBeanClassName as it overwrites 'beanClass'
      setScope(SCOPE_SINGLETON);
      setLazyInit(true);
    }
  }
}
