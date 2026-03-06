package datadog.communication.http.ahc;

import datadog.communication.http.client.HttpClientFacade;
import datadog.communication.http.client.HttpClientFactory;

/** Factory entry-point for Apache HttpComponents async-backed clients. */
public final class ApacheAsyncHttpClientFactory implements HttpClientFactory {
  private final ApacheAsyncHttpClientBuilder builder;

  private ApacheAsyncHttpClientFactory(ApacheAsyncHttpClientBuilder builder) {
    this.builder = builder;
  }

  public static ApacheAsyncHttpClientBuilder builder() {
    return new ApacheAsyncHttpClientBuilder();
  }

  public static ApacheAsyncHttpClientFactory fromBuilder(ApacheAsyncHttpClientBuilder builder) {
    return new ApacheAsyncHttpClientFactory(builder);
  }

  @Override
  public HttpClientFacade create() {
    return builder.build();
  }
}
