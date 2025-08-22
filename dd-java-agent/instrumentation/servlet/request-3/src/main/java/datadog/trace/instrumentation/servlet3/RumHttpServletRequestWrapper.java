package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;

import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

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
    if (maybeRumWrappedResponse instanceof RumHttpServletResponseWrapper) {
      ((RumHttpServletResponseWrapper) maybeRumWrappedResponse).commit();
      ((RumHttpServletResponseWrapper) maybeRumWrappedResponse).stopFiltering();
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
