package datadog.trace.instrumentation.netty41.server;

import io.netty.channel.CombinedChannelDuplexHandler;

public class HttpServerTracingHandler
    extends CombinedChannelDuplexHandler<
        HttpServerRequestTracingHandler, HttpServerResponseTracingHandler> {

  public HttpServerTracingHandler() {
    super(HttpServerRequestTracingHandler.INSTANCE, HttpServerResponseTracingHandler.INSTANCE);
  }
}
