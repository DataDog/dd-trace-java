package datadog.trace.instrumentation.httpclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class JavaNetClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse<?>> {
  public static final String INSTRUMENTATION_NAME = "java-http-client";
  public static final CharSequence COMPONENT = UTF8BytesString.create(INSTRUMENTATION_NAME);

  public static final JavaNetClientDecorator DECORATE = new JavaNetClientDecorator();

  public static final UTF8BytesString OPERATION_NAME =
      UTF8BytesString.create(DECORATE.operationName());

  private static final ThreadLocal<Boolean> INJECT_CONTEXT = new ThreadLocal<>();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT;
  }

  @Override
  protected String method(HttpRequest httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URI url(HttpRequest httpRequest) {
    return httpRequest.uri();
  }

  @Override
  protected Object sourceUrl(final HttpRequest request) {
    return request.uri();
  }

  @Override
  protected int status(HttpResponse<?> httpResponse) {
    return httpResponse.statusCode();
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    return request.headers().firstValue(headerName).orElse(null);
  }

  @Override
  protected String getResponseHeader(HttpResponse<?> response, String headerName) {
    return response.headers().firstValue(headerName).orElse(null);
  }

  /**
   * Checks whether context injection into HTTP headers is currently allowed.
   *
   * @return {@code true} if context injection is allowed for the current thread, {@code false}
   *     otherwise
   */
  public boolean isContextInjectionAllowed() {
    return INJECT_CONTEXT.get() != null && INJECT_CONTEXT.get();
  }

  /** Enables context injection into HTTP headers for the current thread. */
  public void allowContextInjection() {
    INJECT_CONTEXT.set(true);
  }

  /** Disables context injection into HTTP headers for the current thread. */
  public void blockContextInjection() {
    INJECT_CONTEXT.remove();
  }
}
