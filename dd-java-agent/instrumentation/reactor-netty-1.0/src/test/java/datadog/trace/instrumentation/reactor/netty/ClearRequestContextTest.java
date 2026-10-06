package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.Channel;
import io.netty.util.DefaultAttributeMap;
import org.junit.jupiter.api.Test;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.client.HttpClientRequest;

class ClearRequestContextTest {
  @Test
  void clearsReleasedRequestContext() {
    Fixture fixture = new Fixture();

    fixture.cleanup.accept(fixture.connection);

    assertNull(fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(fixture.channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
  }

  @Test
  void leavesUnfinishedClientSpanForNettyCleanup() {
    Fixture fixture = new Fixture();
    Context clientContext = Context.root().with(mock(AgentSpan.class, CALLS_REAL_METHODS));
    fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).set(clientContext);

    fixture.cleanup.accept(fixture.connection);

    assertSame(clientContext, fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertSame(fixture.parent, fixture.channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
  }

  @Test
  void preservesReusedChannelEvenWhenNextRequestHasTheSameParent() {
    Fixture fixture = new Fixture();
    ChannelOperations<?, ?> nextRequest = mock(ChannelOperations.class);
    when(nextRequest.as(ChannelOperations.class)).thenReturn(nextRequest);
    Connection.from(fixture.channel).rebind(nextRequest);

    fixture.cleanup.accept(fixture.connection);

    assertSame(fixture.context, fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertSame(fixture.parent, fixture.channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
  }

  private static class Fixture {
    final AgentSpan parent = mock(AgentSpan.class, CALLS_REAL_METHODS);
    final Context context = Context.root().with(parent);
    final Channel channel = mock(Channel.class);
    final HttpClientRequest request =
        mock(HttpClientRequest.class, withSettings().extraInterfaces(Connection.class));
    final Connection connection = (Connection) request;
    final ClearRequestContext cleanup;

    @SuppressWarnings("unchecked")
    Fixture() {
      DefaultAttributeMap attributes = new DefaultAttributeMap();
      when(channel.hasAttr(any()))
          .thenAnswer(invocation -> attributes.hasAttr(invocation.getArgument(0)));
      when(channel.attr(any()))
          .thenAnswer(invocation -> attributes.attr(invocation.getArgument(0)));
      when(connection.channel()).thenReturn(channel);
      channel.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
      channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).set(parent);
      ContextStore<HttpClientRequest, Context> contexts = mock(ContextStore.class);
      when(contexts.remove(request)).thenReturn(context);
      cleanup = new ClearRequestContext(contexts);
    }
  }
}
