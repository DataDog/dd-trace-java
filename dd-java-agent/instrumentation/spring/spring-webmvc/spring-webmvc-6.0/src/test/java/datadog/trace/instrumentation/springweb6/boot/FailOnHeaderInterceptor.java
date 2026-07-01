package datadog.trace.instrumentation.springweb6.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aborts the request from {@code preHandle} when the {@code fail} header is set, so the controller
 * is never invoked. Test to verify the route is still named on the server span in that case (see
 * {@code HandlerMappingAdvice}).
 */
public class FailOnHeaderInterceptor implements HandlerInterceptor {
  @Override
  public boolean preHandle(
      final HttpServletRequest request, final HttpServletResponse response, final Object handler) {
    if ("true".equalsIgnoreCase(request.getHeader("fail"))) {
      throw new RuntimeException("Stop here");
    }
    return true;
  }
}
