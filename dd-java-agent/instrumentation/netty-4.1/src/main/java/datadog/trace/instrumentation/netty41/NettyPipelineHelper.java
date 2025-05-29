package datadog.trace.instrumentation.netty41;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

public class NettyPipelineHelper {
  public static void addHandlerAfter(
      final ChannelPipeline pipeline, final String name, final ChannelHandler... toAdd) {
    String handlerName = name;
    for (ChannelHandler handler : toAdd) {
      ChannelHandler existing = pipeline.get(handler.getClass());
      if (existing != null) {
        pipeline.remove(existing);
      }
      pipeline.addAfter(handlerName, null, handler);
      ChannelHandlerContext handlerContext = pipeline.context(handler);
      if (handlerContext != null) {
        handlerName = handlerContext.name();
      }
    }
  }

  public static void addHandlerAfter(
      final ChannelPipeline pipeline, final ChannelHandler handler, final ChannelHandler... toAdd) {
    ChannelHandlerContext handlerContext = pipeline.context(handler);
    if (handlerContext != null) {
      String handlerName = handlerContext.name();
      addHandlerAfter(pipeline, handlerName, toAdd);
    }
  }
}
