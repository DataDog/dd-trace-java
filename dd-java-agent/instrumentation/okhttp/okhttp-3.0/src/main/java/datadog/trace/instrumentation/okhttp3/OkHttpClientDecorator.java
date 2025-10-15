package datadog.trace.instrumentation.okhttp3;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class OkHttpClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence OKHTTP = UTF8BytesString.create("okhttp");
  public static final OkHttpClientDecorator DECORATE = new OkHttpClientDecorator();

  public static final CharSequence OKHTTP_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"okhttp", "okhttp-3"};
  }

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return OKHTTP;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URI url(final Request httpRequest) {
    return httpRequest.url().uri();
  }

  @Override
  protected HttpUrl sourceUrl(final Request httpRequest) {
    return httpRequest.url();
  }

  @Override
  protected int status(final Response httpResponse) {
    return httpResponse.code();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return request.header(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.header(headerName);
  }

  /** Overridden by {@link AppSecInterceptor} */
  @Override
  protected void onHttpClientRequest(AgentSpan span, String url) {
    // do nothing
  }
}
