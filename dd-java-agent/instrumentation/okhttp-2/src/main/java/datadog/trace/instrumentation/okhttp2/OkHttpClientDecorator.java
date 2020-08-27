package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;

public class OkHttpClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final OkHttpClientDecorator DECORATE = new OkHttpClientDecorator();

  @Override
  protected String method(Request request) {
    return request.method();
  }

  @Override
  protected URI url(Request request) throws URISyntaxException {
    return request.url().toURI();
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
  protected String component() {
    return "okhttp";
  }
}
