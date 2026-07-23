package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

class HttpServerResponseTracingHandlerTest extends AbstractInstrumentationTest {

  @Test
  void finishesMirroredContextWhenRequestQueueIsAbsent() {
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE);
    AgentSpan span = startSpan("netty", "mirrored-http2-server");
    channel.attr(CONTEXT_ATTRIBUTE_KEY).set(span);

    assertTrue(channel.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, OK)));

    FullHttpResponse response = channel.readOutbound();
    assertNotNull(response);
    response.release();
    assertNull(channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
    channel.finishAndReleaseAll();
    assertTraces(trace(span().root().operationName("mirrored-http2-server")));
  }

  @Test
  void forwardsLastContentBeforeFinalResponseWithoutCompletingContext() {
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE);
    ServerRequestContext serverContext = ServerRequestContext.add(channel, Context.root(), null);
    LastHttpContent lastContent = new DefaultLastHttpContent();

    assertTrue(channel.writeOutbound(lastContent));

    assertSame(serverContext, ServerRequestContext.nextResponse(channel));
    LastHttpContent forwarded = channel.readOutbound();
    assertSame(lastContent, forwarded);
    forwarded.release();
    channel.finishAndReleaseAll();
  }

  @Test
  void headerOnlyResponseCompletesContextAndWritesSyntheticLastContent() {
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE);
    AgentSpan span = startSpan("netty", "header-only-server");
    ServerRequestContext.add(channel, span, null);
    ServerRequestContext nextServerContext =
        ServerRequestContext.add(channel, Context.root(), null);
    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, NO_CONTENT);

    assertTrue(channel.writeOutbound(response));

    assertSame(nextServerContext, ServerRequestContext.nextResponse(channel));
    HttpResponse forwarded = channel.readOutbound();
    assertSame(response, forwarded);
    ReferenceCountUtil.release(forwarded);
    assertSyntheticLastContent(channel);

    assertTrue(channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT));

    assertSame(nextServerContext, ServerRequestContext.nextResponse(channel));
    ReferenceCountUtil.release(channel.readOutbound());
    ServerRequestContext.remove(channel, nextServerContext);
    channel.finishAndReleaseAll();
    assertTraces(trace(span().root().operationName("header-only-server")));
  }

  @Test
  void rawFixedLengthBodyCompletesContextAndWritesSyntheticLastContent() {
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE);
    AgentSpan span = startSpan("netty", "raw-body-server");
    ServerRequestContext.add(channel, span, null);
    ServerRequestContext nextServerContext =
        ServerRequestContext.add(channel, Context.root(), null);
    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
    response.headers().set(CONTENT_LENGTH, 4);

    assertTrue(channel.writeOutbound(response));
    ReferenceCountUtil.release(channel.readOutbound());
    assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4})));

    assertSame(nextServerContext, ServerRequestContext.nextResponse(channel));
    ReferenceCountUtil.release(channel.readOutbound());
    assertSyntheticLastContent(channel);

    assertTrue(channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT));

    assertSame(nextServerContext, ServerRequestContext.nextResponse(channel));
    ReferenceCountUtil.release(channel.readOutbound());
    ServerRequestContext.remove(channel, nextServerContext);
    channel.finishAndReleaseAll();
    assertTraces(trace(span().root().operationName("raw-body-server")));
  }

  @Test
  void doesNotThrowOnMalformedContentLength() {
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE);
    AgentSpan span = startSpan("netty", "malformed-content-length-server");
    ServerRequestContext.add(channel, span, null);
    FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
    response.headers().set(CONTENT_LENGTH, "malformed");

    assertDoesNotThrow(() -> assertTrue(channel.writeOutbound(response)));

    FullHttpResponse forwarded = channel.readOutbound();
    assertSame(response, forwarded);
    forwarded.release();
    channel.finishAndReleaseAll();
    assertTraces(trace(span().root().operationName("malformed-content-length-server")));
  }

  @Test
  void nettyEncoderRequiresLastContentBeforeNextKeepAliveResponse() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpResponseEncoder());
    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
    response.headers().set(CONNECTION, KEEP_ALIVE);
    response.headers().set(CONTENT_LENGTH, 4);

    assertTrue(channel.writeOutbound(response));
    ReferenceCountUtil.release(channel.readOutbound());
    assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4})));
    ReferenceCountUtil.release(channel.readOutbound());

    EncoderException exception =
        assertThrows(
            EncoderException.class,
            () -> channel.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, OK)));

    assertTrue(exception.getCause() instanceof IllegalStateException);

    assertTrue(channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT));
    ReferenceCountUtil.release(channel.readOutbound());
    assertTrue(channel.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, OK)));
    ReferenceCountUtil.release(channel.readOutbound());
    channel.finishAndReleaseAll();
  }

  @Test
  void fullWebsocketUpgradeCompletesContextAndPreservesHandshakeSpan() {
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE);
    AgentSpan span = startSpan("netty", "websocket-handshake-server");
    ServerRequestContext.add(channel, span, null);
    FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, SWITCHING_PROTOCOLS);
    response.headers().set(UPGRADE, "websocket");

    assertTrue(channel.writeOutbound(response));

    assertNull(ServerRequestContext.nextResponse(channel));
    assertNotNull(channel.attr(WEBSOCKET_SENDER_HANDLER_CONTEXT).get());
    ReferenceCountUtil.release(channel.readOutbound());
    channel.finishAndReleaseAll();
    assertTraces(trace(span().root().operationName("websocket-handshake-server")));
  }

  private static void assertSyntheticLastContent(EmbeddedChannel channel) {
    Object syntheticLastContent = channel.readOutbound();
    assertTrue(syntheticLastContent instanceof LastHttpContent);
    ReferenceCountUtil.release(syntheticLastContent);
  }
}
