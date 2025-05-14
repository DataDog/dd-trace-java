package datadog.trace.instrumentation.netty41.server.websocket;

import io.netty.channel.CombinedChannelDuplexHandler;

public class WebSocketServerTracingHandler
    extends CombinedChannelDuplexHandler<
        WebSocketServerInboundTracingHandler, WebSocketServerOutboundTracingHandler> {

  public WebSocketServerTracingHandler() {
    super(
        WebSocketServerInboundTracingHandler.INSTANCE,
        WebSocketServerOutboundTracingHandler.INSTANCE);
  }
}
