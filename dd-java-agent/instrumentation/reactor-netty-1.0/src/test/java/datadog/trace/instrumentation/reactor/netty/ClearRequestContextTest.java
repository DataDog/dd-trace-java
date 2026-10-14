package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.instrumentation.netty41.client.HttpClientResponseTracingHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;
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

  @TableTest({
    "Scenario                | Event    | Disposed Connection",
    "close                   | close    | false              ",
    "close after disposal    | close    | true               ",
    "response                | response | false              ",
    "response after disposal | response | true               ",
    "error                   | error    | false              ",
    "error after disposal    | error    | true               "
  })
  void clearsContextAfterNettyFinishesClientSpan(String event, boolean disposedConnection) {
    Fixture fixture = new Fixture();
    AgentSpan client = mock(AgentSpan.class, CALLS_REAL_METHODS);
    Context clientContext = fixture.context.with(client);
    fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).set(clientContext);
    fixture.channel.pipeline().addLast(HttpClientResponseTracingHandler.INSTANCE);

    fixture.cleanup.accept(fixture.connection);

    assertSame(clientContext, fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    if (disposedConnection) {
      when(fixture.connection.channel()).thenReturn(new EmbeddedChannel());
    }
    switch (event) {
      case "close":
        fixture.channel.close();
        break;
      case "response":
        fixture.channel.writeInbound(new DefaultHttpResponse(HTTP_1_1, OK));
        break;
      case "error":
        fixture
            .channel
            .pipeline()
            .addLast(
                new ChannelInboundHandlerAdapter() {
                  @Override
                  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    // Consume the error after Netty has finished the client span.
                  }
                });
        fixture.channel.pipeline().fireExceptionCaught(new IOException("request failed"));
        break;
      default:
        throw new AssertionError("Unknown event: " + event);
    }

    verify(client).finish();
    assertNull(fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(fixture.channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void clearsBaggageOnlyContext(boolean withNoopParent) {
    Context baggageContext = Context.root().with(Baggage.create(singletonMap("user.id", "abc123")));
    Fixture fixture = new Fixture(baggageContext);
    if (withNoopParent) {
      fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).set(baggageContext.with(noopSpan()));
      fixture.channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).set(noopSpan());
    }

    fixture.cleanup.accept(fixture.connection);

    assertNull(fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(fixture.channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
  }

  @Test
  void deferredCleanupPreservesNextRequestWithSameParent() {
    Fixture fixture = new Fixture();
    fixture
        .channel
        .attr(CONTEXT_ATTRIBUTE_KEY)
        .set(fixture.context.with(mock(AgentSpan.class, CALLS_REAL_METHODS)));
    fixture.cleanup.accept(fixture.connection);
    ChannelOperations<?, ?> nextRequest = mock(ChannelOperations.class);
    when(nextRequest.as(ChannelOperations.class)).thenReturn(nextRequest);
    Connection.from(fixture.channel).rebind(nextRequest);
    fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).set(fixture.context);

    fixture.channel.close();

    assertSame(fixture.context, fixture.channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
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
    final AgentSpan parent;
    final Context context;
    final EmbeddedChannel channel = new EmbeddedChannel();
    final HttpClientRequest request =
        mock(HttpClientRequest.class, withSettings().extraInterfaces(Connection.class));
    final Connection connection = (Connection) request;
    final ClearRequestContext cleanup;

    Fixture() {
      this(Context.root().with(mock(AgentSpan.class, CALLS_REAL_METHODS)));
    }

    @SuppressWarnings("unchecked")
    Fixture(Context context) {
      this.context = context;
      this.parent = AgentSpan.fromContext(context);
      when(connection.channel()).thenReturn(channel);
      channel.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
      channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).set(parent);
      ContextStore<HttpClientRequest, Context> contexts = mock(ContextStore.class);
      when(contexts.remove(request)).thenReturn(context).thenReturn(null);
      cleanup = new ClearRequestContext(contexts);
    }
  }
}
