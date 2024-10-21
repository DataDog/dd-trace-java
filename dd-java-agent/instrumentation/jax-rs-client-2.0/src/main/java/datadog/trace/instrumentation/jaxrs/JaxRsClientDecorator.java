package datadog.trace.instrumentation.jaxrs;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientResponseContext;

public class JaxRsClientDecorator
    extends HttpClientDecorator<ClientRequestContext, ClientResponseContext> {
  public static final CharSequence JAX_RS_CLIENT = UTF8BytesString.create("jax-rs.client");
  public static final JaxRsClientDecorator DECORATE = new JaxRsClientDecorator();

  public static final CharSequence JAX_RS_CLIENT_CALL =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jax-rs", "jaxrs", "jax-rs-client"};
  }

  @Override
  protected CharSequence component() {
    return JAX_RS_CLIENT;
  }

  @Override
  protected String method(final ClientRequestContext httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final ClientRequestContext httpRequest) {
    return httpRequest.getUri();
  }

  @Override
  protected int status(final ClientResponseContext httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(ClientRequestContext request, String headerName) {
    return request.getHeaderString(headerName);
  }

  @Override
  protected String getResponseHeader(ClientResponseContext response, String headerName) {
    return response.getHeaderString(headerName);
  }
}
