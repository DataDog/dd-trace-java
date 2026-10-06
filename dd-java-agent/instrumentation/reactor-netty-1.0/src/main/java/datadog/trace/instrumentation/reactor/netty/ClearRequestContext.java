package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.Channel;
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
    ChannelOperations<?, ?> current = ChannelOperations.get(channel);
    if (current != null && current != connection) {
      return; // The pooled channel already belongs to another request.
    }
    AgentSpan parent = AgentSpan.fromContext(requestContext);
    if (parent == null) {
      return;
    }
    Attribute<Context> attribute = channel.attr(CONTEXT_ATTRIBUTE_KEY);
    Context stored = attribute.get();
    // Leave a live client span for Netty's error/close handler to finish.
    if (stored != null
        && AgentSpan.fromContext(stored) == parent
        && attribute.compareAndSet(stored, null)) {
      channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).compareAndSet(parent, null);
    }
  }
}
