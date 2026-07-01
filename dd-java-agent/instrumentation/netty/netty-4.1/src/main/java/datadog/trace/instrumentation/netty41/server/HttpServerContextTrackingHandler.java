package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.AttributeKeys.PARENT_CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;

@ChannelHandler.Sharable
public class HttpServerContextTrackingHandler extends ChannelInboundHandlerAdapter {
  public static final HttpServerContextTrackingHandler INSTANCE =
      new HttpServerContextTrackingHandler();

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    if (msg instanceof HttpRequest) {
      final Context parentContext = DECORATE.extract(((HttpRequest) msg).headers());
      ctx.channel().attr(PARENT_CONTEXT_ATTRIBUTE_KEY).set(parentContext);
    }
    ctx.fireChannelRead(msg);
  }
}
