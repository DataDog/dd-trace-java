package datadog.trace.instrumentation.jaxrs.v1;

import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;

public class JaxRsClientV1Decorator extends HttpClientDecorator<ClientRequest, ClientResponse> {

  public static final CharSequence JAX_RS_CLIENT = UTF8BytesString.create("jax-rs.client");

  public static final JaxRsClientV1Decorator DECORATE = new JaxRsClientV1Decorator();
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
  protected String method(final ClientRequest httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final ClientRequest httpRequest) {
    return httpRequest.getURI();
  }

  @Override
  protected int status(final ClientResponse clientResponse) {
    return clientResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(ClientRequest request, String headerName) {
    Object headerValue = request.getHeaders().getFirst(headerName);
    if (null != headerValue) {
      return headerValue.toString();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(ClientResponse response, String headerName) {
    return response.getHeaders().getFirst(headerName);
  }
}
