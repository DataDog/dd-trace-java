package datadog.trace.instrumentation.springwebflux.client;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;

public class SpringWebfluxHttpClientDecorator
    extends HttpClientDecorator<ClientRequest, ClientResponse> {
  public static final CharSequence SPRING_WEBFLUX_CLIENT =
      UTF8BytesString.create("spring-webflux-client");
  public static final CharSequence CANCELLED = UTF8BytesString.create("cancelled");
  public static final CharSequence CANCELLED_MESSAGE =
      UTF8BytesString.create("The subscription was cancelled");

  public static final SpringWebfluxHttpClientDecorator DECORATE =
      new SpringWebfluxHttpClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  public void onCancel(final AgentSpan span) {
    span.setTag("event", CANCELLED);
    span.setTag("message", CANCELLED_MESSAGE);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-webflux", "spring-webflux-client"};
  }

  @Override
  protected CharSequence component() {
    return SPRING_WEBFLUX_CLIENT;
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
  protected int status(final ClientResponse httpResponse) {
    final Integer code = StatusCodes.STATUS_CODE_FUNCTION.apply(httpResponse);
    return code != null ? code : 0;
  }

  @Override
  protected String getRequestHeader(ClientRequest request, String headerName) {
    return request.headers().getFirst(headerName);
  }

  @Override
  protected String getResponseHeader(ClientResponse response, String headerName) {
    return response.headers().asHttpHeaders().getFirst(headerName);
  }
}
