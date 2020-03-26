package datadog.trace.instrumentation.jaxrs.v1;

import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;

public class JaxRsClientV1Decorator extends HttpClientDecorator<ClientRequest, ClientResponse> {
  public static final JaxRsClientV1Decorator DECORATE = new JaxRsClientV1Decorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jax-rs", "jaxrs", "jax-rs-client"};
  }

  @Override
  protected String component() {
    return "jax-rs.client";
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
  protected Integer status(final ClientResponse clientResponse) {
    return clientResponse.getStatus();
  }
}
