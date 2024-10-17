package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;

public class OkHttpClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence OKHTTP = UTF8BytesString.create("okhttp");
  public static final OkHttpClientDecorator DECORATE = new OkHttpClientDecorator();
  public static final CharSequence OKHTTP_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String method(Request request) {
    return request.method();
  }

  @Override
  protected URI url(Request request) throws URISyntaxException {
    return request.url().toURI();
  }

  @Override
  protected String sourceUrl(final Request request) {
    final PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
    String url = request.urlString();
    if (propagationModule != null) {
      propagationModule.taintObjectIfTainted(url, request.url());
    }
    return url;
  }

  @Override
  protected int status(Response response) {
    return response.code();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"okhttp", "okhttp-2"};
  }

  @Override
  protected CharSequence component() {
    return OKHTTP;
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return request.header(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.header(headerName);
  }
}
