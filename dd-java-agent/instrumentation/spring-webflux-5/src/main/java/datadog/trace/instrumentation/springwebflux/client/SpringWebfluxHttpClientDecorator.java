package datadog.trace.instrumentation.springwebflux.client;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

  private static final MethodHandle RAW_STATUS_CODE = findRawStatusCode();

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
    if (null != RAW_STATUS_CODE) {
      try {
        return (int) RAW_STATUS_CODE.invokeExact(httpResponse);
      } catch (Throwable ignored) {
      }
    }
    return httpResponse.statusCode().value();
  }

  @Override
  protected String getRequestHeader(ClientRequest request, String headerName) {
    return request.headers().getFirst(headerName);
  }

  @Override
  protected String getResponseHeader(ClientResponse response, String headerName) {
    return response.headers().asHttpHeaders().getFirst(headerName);
  }

  private static MethodHandle findRawStatusCode() {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(ClientResponse.class, "rawStatusCode", MethodType.methodType(int.class));
    } catch (IllegalAccessException | NoSuchMethodException e) {
      return null;
    }
  }
}
