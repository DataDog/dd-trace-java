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
import java.util.Arrays;
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
      response.setHeader("ext_trace_id", GlobalTracer.get().getTraceId());
      boolean tracerHeader = Config.get().isTracerHeaderEnabled();
      boolean requestBodyEnabled = Config.get().isTracerRequestBodyEnabled();
      boolean responseBodyEnabled = Config.get().isTracerResponseBodyEnabled();
      if (!(tracerHeader || requestBodyEnabled || responseBodyEnabled)) {
        log.debug("尚未开启 request|response 功能");
        filterChain.doFilter(request, response);
        return;
      }

      if (tracerHeader && !requestBodyEnabled && !responseBodyEnabled) {
        filterChain.doFilter(request, response);
        buildHeaderTags(span,request, response);
        return;
      }

      ContentCachingRequestWrapper requestWrapper = null;
      if (requestBodyEnabled){
        requestWrapper = new ContentCachingRequestWrapper(request);
      }
      String contextType = requestWrapper==null?request.getContentType():requestWrapper.getContentType();
      String methodType = requestWrapper==null?request.getMethod():requestWrapper.getMethod();
      String url = requestWrapper==null?request.getRequestURI():requestWrapper.getRequestURI();
      boolean hasBlackList = false;
      if (!isEmpty(Config.get().getTracerResponseBodyBlackListUrls())){
        hasBlackList = Arrays.stream(Config.get().getTracerResponseBodyBlackListUrls().split(",")).anyMatch(uri -> uri.equals(url));
      }
      ContentCachingResponseWrapper responseWrapper = null;
      boolean responseBodyEnabledTmp = responseBodyEnabled && !hasBlackList;
      if (responseBodyEnabledTmp){
        responseWrapper = new ContentCachingResponseWrapper(response);
      }

      filterChain.doFilter(requestWrapper==null?request:requestWrapper, responseWrapper==null?response:responseWrapper);

      byte[] data =null;
      if (responseBodyEnabledTmp) {
        // 必须放到 filterChain.doFilter 之后，否则 responseWrapper.getContentAsByteArray() 为空
        data = responseWrapper.getContentAsByteArray();
        responseWrapper.copyBodyToResponse();
      }
      if (tracerHeader) {
        buildHeaderTags(span,requestWrapper==null?request:requestWrapper, responseWrapper==null?response:responseWrapper);
      }

      if (Config.get().isTracerRequestBodyEnabled()
          && "POST".equalsIgnoreCase(methodType)
          && contextType != null
          && (contextType.contains("application/json"))) {
        span.setTag("request_body", new String(requestWrapper.getContentAsByteArray()));
      }
      int dataLength = data==null?0:data.length;
      log.debug(
          "traceId:{},spanId:{},dataLength:{},responseBodyEnabled:{}",
          GlobalTracer.get().getTraceId(),
          span.getSpanId(),
          dataLength,
          responseBodyEnabled);
      if (responseBodyEnabledTmp) {
        if (responseWrapper.getContentType() != null
            && (responseWrapper.getContentType().contains("application/json")
            || responseWrapper.getContentType().contains("text/plain"))) {
          try {
            if (dataLength < 1024 * 2) {
              span.setTag("response_body", new String(data));
            } else {
              span.setTag("response_body", new String(data).substring(0, 1024 * 2 - 1));
            }
          } catch (Exception e) {
            log.error(
                "traceId:{},span:{},response_body",
                GlobalTracer.get().getTraceId(),
                span.getSpanId(),
                e.getMessage());
          }
        }
      }
    } else {
      filterChain.doFilter(request, response);
    }


  }
  private boolean isEmpty(String str) {
    return str == null || str.length() == 0;
  }

  private void buildHeaderTags(AgentSpan span,final HttpServletRequest request, final HttpServletResponse response) {
    StringBuffer requestHeader = new StringBuffer("");
    StringBuffer responseHeader = new StringBuffer("");
    Enumeration<String> headerNames = request.getHeaderNames();
    int count = 0;
    while (headerNames.hasMoreElements()) {
      if (count == 0) {
        requestHeader.append("{");
      } else {
        requestHeader.append(",\n");
      }
      String headerName = headerNames.nextElement();
      requestHeader
          .append("\"")
          .append(headerName)
          .append("\":")
          .append("\"")
          .append(request.getHeader(headerName).replace("\"", ""))
          .append("\"");
      count++;
    }
    if (count > 0) {
      requestHeader.append("}");
    }
    count = 0;
    for (String headerName : response.getHeaderNames()) {
      if (count == 0) {
        responseHeader.append("{");
      } else {
        responseHeader.append(",\n");
      }
      responseHeader
          .append("\"")
          .append(headerName)
          .append("\":")
          .append("\"")
          .append(response.getHeader(headerName))
          .append("\"");
      count++;
    }

    if (count > 0) {
      responseHeader.append("}");
    }
    span.setTag("request_header", requestHeader.toString());
    span.setTag("response_header", responseHeader.toString());
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
