package datadog.trace.instrumentation.netty38.server;

import org.jboss.netty.channel.ChannelDownstreamHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockAllWritesHandler extends SimpleChannelDownstreamHandler {
  public static final ChannelDownstreamHandler INSTANCE = new BlockAllWritesHandler();
  private static final Logger log = LoggerFactory.getLogger(BlockAllWritesHandler.class);

  private BlockAllWritesHandler() {}

  @Override
  public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) {
    if (e.getMessage() instanceof HttpResponse) {
      log.debug("Blocking write of {}", e);
      e.getFuture().setSuccess();
    } else {
      ctx.sendDownstream(e);
    }
  }
}
