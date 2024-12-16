package datadog.trace.instrumentation.jetty_client91;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.util.List;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class JettyClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence JETTY_CLIENT = UTF8BytesString.create("jetty-client");
  public static final JettyClientDecorator DECORATE = new JettyClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jetty-client"};
  }

  @Override
  protected CharSequence component() {
    return JETTY_CLIENT;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final Request httpRequest) {
    return httpRequest.getURI();
  }

  @Override
  protected int status(final Response httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    List<String> result = request.getHeaders().getValuesList(headerName);
    return String.join(",", result);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHeaders().get(headerName);
  }
}
