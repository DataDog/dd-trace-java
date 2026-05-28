package datadog.trace.instrumentation.quarkus_rest_client_reactive_javax;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientResponseContext;

public class QuarkusRestClientJavaxDecorator
    extends HttpClientDecorator<ClientRequestContext, ClientResponseContext> {

  public static final CharSequence QUARKUS_REST_CLIENT =
      UTF8BytesString.create("quarkus-rest-client-reactive");
  public static final QuarkusRestClientJavaxDecorator DECORATE =
      new QuarkusRestClientJavaxDecorator();
  public static final CharSequence QUARKUS_REST_CLIENT_CALL =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {
      "quarkus-rest-client-reactive", "quarkus-rest-client", "microprofile-rest-client"
    };
  }

  @Override
  protected CharSequence component() {
    return QUARKUS_REST_CLIENT;
  }

  @Override
  protected String method(final ClientRequestContext request) {
    return request.getMethod();
  }

  @Override
  protected URI url(final ClientRequestContext request) {
    return request.getUri();
  }

  @Override
  protected int status(final ClientResponseContext response) {
    return response.getStatus();
  }

  @Override
  protected String getRequestHeader(final ClientRequestContext request, final String headerName) {
    return request.getHeaderString(headerName);
  }

  @Override
  protected String getResponseHeader(
      final ClientResponseContext response, final String headerName) {
    return response.getHeaderString(headerName);
  }
}
