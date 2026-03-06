package datadog.communication.http.netty;

import datadog.communication.http.client.HttpClientFacade;
import datadog.communication.http.client.HttpClientFactory;

/** Factory entry-point for Netty-backed clients. */
public final class NettyHttpClientFactory implements HttpClientFactory {
  private final NettyHttpClientBuilder builder;

  private NettyHttpClientFactory(NettyHttpClientBuilder builder) {
    this.builder = builder;
  }

  public static NettyHttpClientBuilder builder() {
    return new NettyHttpClientBuilder();
  }

  public static NettyHttpClientFactory fromBuilder(NettyHttpClientBuilder builder) {
    return new NettyHttpClientFactory(builder);
  }

  @Override
  public HttpClientFacade create() {
    return builder.build();
  }
}
