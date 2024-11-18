package datadog.trace.instrumentation.springweb6;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

public class HandlerMappingResourceNameFilter extends OncePerRequestFilter implements Ordered {

  private static final Logger log = LoggerFactory.getLogger(HandlerMappingResourceNameFilter.class);
  private final List<HandlerMapping> handlerMappings = new CopyOnWriteArrayList<>();

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (parentSpan instanceof AgentSpan) {
      PathMatchingHttpServletRequestWrapper wrappedRequest =
          new PathMatchingHttpServletRequestWrapper(request);
      AgentSpan span = (AgentSpan) parentSpan;
      try {
        if (findMapping(wrappedRequest)) {
          // Name the parent span based on the matching pattern
          // Let the parent span resource name be set with the attribute set in findMapping.
          SpringWebHttpServerDecorator.DECORATE.onRequest(
              span, wrappedRequest, wrappedRequest, null);
        }
      } catch (final Exception ignored) {
        // mapping.getHandler() threw exception.  Ignore
      }
      ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
      ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
      filterChain.doFilter(requestWrapper, wrapper);
      wrapper.copyBodyToResponse();

      String contextType = requestWrapper.getContentType();
      String methodType = requestWrapper.getMethod();

      boolean tracerHeader = Config.get().isTracerHeaderEnabled();
      if (tracerHeader) {
        wrapper.addHeader("guance_trace_id", GlobalTracer.get().getTraceId());
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
        for (String headerName : wrapper.getHeaderNames()) {
          if (count==0){
            responseHeader.append("{");
          }else{
            responseHeader.append(",\n");
          }
          responseHeader.append("\"").append(headerName).append("\":").append("\"").append(wrapper.getHeader(headerName)).append("\"");
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
      log.debug("response.getContentType() >>>> :{},traceId:{},responseBodyEnabled:{}",wrapper.getContentType(),GlobalTracer.get().getTraceId(),Config.get().isTracerResponseBodyEnabled());
      if (Config.get().isTracerResponseBodyEnabled()) {
        if (wrapper.getContentType() != null && (wrapper.getContentType().contains("application/json") || wrapper.getContentType().contains("text/plain"))) {
          byte[] data = wrapper.getContentAsByteArray();
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
