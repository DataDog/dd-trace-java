package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;

import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

public class RumHttpServletRequestWrapper extends HttpServletRequestWrapper {

  private HttpServletResponse response;

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
    // rewrap it
    if (servletResponse instanceof HttpServletResponse) {
      this.response =
          new RumHttpServletResponseWrapper(this, (HttpServletResponse) servletResponse);
      servletRequest.setAttribute(DD_RUM_INJECTED, this.response);
    }
    return super.startAsync(servletRequest, this.response);
  }
}
