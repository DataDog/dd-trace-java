package datadog.trace.instrumentation.netty40.server.websocket;

import io.netty.channel.CombinedChannelDuplexHandler;

public class WebSocketServerTracingHandler
    extends CombinedChannelDuplexHandler<
        WebSocketServerRequestTracingHandler, WebSocketServerResponseTracingHandler> {

  public WebSocketServerTracingHandler() {
    super(
        WebSocketServerRequestTracingHandler.INSTANCE,
        WebSocketServerResponseTracingHandler.INSTANCE);
  }
}
