package datadog.trace.instrumentation.servlet5;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;

import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

public class RumHttpServletRequestWrapper extends HttpServletRequestWrapper {

  private final HttpServletResponse response;

  public RumHttpServletRequestWrapper(
      final HttpServletRequest request, final HttpServletResponse response) {
    super(request);
    this.response = response;
  }

  @Override
  public AsyncContext startAsync() throws IllegalStateException {
    // need to hide this method otherwise we cannot control the wrapped response used asynchronously
    return startAsync(getRequest(), response);
  }

  @Override
  public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
      throws IllegalStateException {
    // deactivate the previous wrapper
    final Object maybeRumWrappedResponse = (servletRequest.getAttribute(DD_RUM_INJECTED));
    if (maybeRumWrappedResponse instanceof RumControllableResponse) {
      ((RumControllableResponse) maybeRumWrappedResponse).commit();
      ((RumControllableResponse) maybeRumWrappedResponse).stopFiltering();
    }
    ServletResponse actualResponse = servletResponse;
    // rewrap it
    if (servletResponse instanceof HttpServletResponse) {
      actualResponse =
          new RumHttpServletResponseWrapper(this, (HttpServletResponse) servletResponse);
      servletRequest.setAttribute(DD_RUM_INJECTED, actualResponse);
    }
    return super.startAsync(servletRequest, actualResponse);
  }
}
