package datadog.trace.instrumentation.springwebflux.client;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;

@Slf4j
public class SpringWebfluxHttpClientDecorator
    extends HttpClientDecorator<ClientRequest, ClientResponse> {

  public static final SpringWebfluxHttpClientDecorator DECORATE =
      new SpringWebfluxHttpClientDecorator();

  public void onCancel(final AgentSpan span) {
    span.setTag("event", "cancelled");
    span.setTag("message", "The subscription was cancelled");
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-webflux", "spring-webflux-client"};
  }

  @Override
  protected String component() {
    return "spring-webflux-client";
  }

  @Override
  protected String method(final ClientRequest httpRequest) {
    return httpRequest.method().name();
  }

  @Override
  protected URI url(final ClientRequest httpRequest) {
    return httpRequest.url();
  }

  @Override
  protected Integer status(final ClientResponse httpResponse) {
    return httpResponse.statusCode().value();
  }
}
