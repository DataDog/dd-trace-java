package datadog.trace.instrumentation.netty41.client;

import io.netty.channel.CombinedChannelDuplexHandler;

public final class HttpClientTracingHandler
    extends CombinedChannelDuplexHandler<
        HttpClientResponseTracingHandler, HttpClientRequestTracingHandler> {

  public HttpClientTracingHandler() {
    super(HttpClientResponseTracingHandler.INSTANCE, HttpClientRequestTracingHandler.INSTANCE);
  }
}
