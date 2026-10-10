package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import java.util.function.Consumer;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.client.HttpClientRequest;

/** Clears request context when a connection is released or disconnected. */
public class ClearRequestContext implements Consumer<Connection> {
  private final ContextStore<HttpClientRequest, Context> requestContexts;

  public ClearRequestContext(ContextStore<HttpClientRequest, Context> requestContexts) {
    this.requestContexts = requestContexts;
  }

  @Override
  public void accept(Connection connection) {
    if (!(connection instanceof HttpClientRequest)) {
      return;
    }
    Context requestContext = requestContexts.remove((HttpClientRequest) connection);
    Channel channel = connection.channel();
    if (requestContext != null && !clear(connection, channel, requestContext)) {
      // The disconnect callback can precede Netty's response/error/close handling.
      channel.pipeline().addFirst(new DeferredCleanup(connection, requestContext));
    }
  }

  private static boolean clear(Connection connection, Channel channel, Context requestContext) {
    ChannelOperations<?, ?> current = ChannelOperations.get(channel);
    if (current != null && current != connection) {
      return true; // The pooled channel already belongs to another request.
    }
    AgentSpan parent = AgentSpan.fromContext(requestContext);
    AgentSpan restoredParent = parent == null ? noopSpan() : parent;
    Attribute<Context> attribute = channel.attr(CONTEXT_ATTRIBUTE_KEY);
    Context stored = attribute.get();
    if (stored == null) {
      return true;
    }
    AgentSpan storedSpan = AgentSpan.fromContext(stored);
    if (storedSpan != parent && storedSpan != restoredParent) {
      return false; // Leave a live client span for Netty's error/close handler to finish.
    }
    if (attribute.compareAndSet(stored, null)) {
      channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).compareAndSet(restoredParent, null);
    }
    return true;
  }

  /** Clears the restored parent after Netty has processed the next response, error, or close. */
  public static class DeferredCleanup extends ChannelInboundHandlerAdapter {
    private final Connection connection;
    private final Context requestContext;

    public DeferredCleanup(Connection connection, Context requestContext) {
      this.connection = connection;
      this.requestContext = requestContext;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      try {
        super.channelRead(ctx, msg);
      } finally {
        removeIfComplete(ctx);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      try {
        super.exceptionCaught(ctx, cause);
      } finally {
        removeIfComplete(ctx);
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      try {
        super.channelInactive(ctx);
      } finally {
        removeIfComplete(ctx);
      }
    }

    private void removeIfComplete(ChannelHandlerContext ctx) {
      // Newer Reactor versions replace connection.channel() with a disposed channel on release.
      if (clear(connection, ctx.channel(), requestContext)) {
        ctx.pipeline().remove(this);
      }
    }
  }
}
