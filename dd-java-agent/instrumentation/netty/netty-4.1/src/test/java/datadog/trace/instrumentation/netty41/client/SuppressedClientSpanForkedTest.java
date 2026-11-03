package datadog.trace.instrumentation.netty41.client;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.suppressNestedClientSpans;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.Subscription;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@WithConfig(key = "trace.otel.semantics.enabled", value = "true")
@WithConfig(key = "appsec.enabled", value = "true")
@WithConfig(key = "appsec.rasp.enabled", value = "true")
class SuppressedClientSpanForkedTest extends AbstractInstrumentationTest {
  @Test
  void restoresUpstreamContextAfterAnErrorBeforeHeaders() {
    AgentSpan parent = AgentTracer.startSpan("test", "parent");
    AgentSpan owner = AgentTracer.startSpan("test", "webclient", parent.spanContext());
    Context parentContext = Context.current().with(parent);
    List<Object> observed = new ArrayList<>();
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new HttpClientTracingHandler(),
            new ChannelInboundHandlerAdapter() {
              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                observed.add(activeSpan());
                owner.finish();
              }

              @Override
              public void channelInactive(ChannelHandlerContext ctx) {
                observed.add(activeSpan());
              }
            });
    try {
      try (ContextScope scope = activateSpan(owner);
          ContextScope suppression = suppressNestedClientSpans(owner, parentContext)) {
        channel.writeOutbound(new DefaultHttpRequest(HTTP_1_1, GET, "/test"));
      }
      channel.pipeline().fireExceptionCaught(new IOException("connection failure"));
      assertSame(parentContext, channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
      channel.close();
      assertSame(owner, observed.get(0));
      assertSame(parent, observed.get(1));
      assertFalse(((DDSpan) parent).isFinished());
    } finally {
      channel.finishAndReleaseAll();
      owner.finish();
      parent.finish();
    }
  }

  @Test
  void preservesANewRequestStartedFromTheResponseCallback() {
    AgentSpan owner = AgentTracer.startSpan("test", "first-request");
    AgentSpan nextOwner = AgentTracer.startSpan("test", "next-request");
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new HttpClientTracingHandler(),
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object message) {
                owner.finish();
                try (ContextScope scope = activateSpan(nextOwner);
                    ContextScope suppression =
                        suppressNestedClientSpans(nextOwner, Context.root())) {
                  ctx.channel().writeAndFlush(new DefaultHttpRequest(HTTP_1_1, GET, "/next"));
                }
              }
            });
    try {
      try (ContextScope scope = activateSpan(owner);
          ContextScope suppression = suppressNestedClientSpans(owner, Context.root())) {
        channel.writeOutbound(new DefaultHttpRequest(HTTP_1_1, GET, "/first"));
      }
      channel.writeInbound(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK));
      assertSame(nextOwner, AgentSpan.fromContext(channel.attr(CONTEXT_ATTRIBUTE_KEY).get()));
    } finally {
      channel.finishAndReleaseAll();
      owner.finish();
      nextOwner.finish();
    }
  }

  @TableTest({
    "Scenario           | Response status | Stream channel",
    "response headers   | 200             | false         ",
    "protocol upgrade   | 101             | false         ",
    "multiplexed stream | 200             | true          "
  })
  void restoresUpstreamContextForBodyErrorsAndClose(int responseStatus, boolean streamChannel) {
    AgentSpan parent = AgentTracer.startSpan("test", "parent");
    AgentSpan owner = AgentTracer.startSpan("test", "webclient", parent.spanContext());
    Context parentContext = Context.current().with(parent);
    List<Object> observed = new ArrayList<>();
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new HttpClientTracingHandler(),
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object message) {
                observed.add(activeSpan());
                if (message instanceof HttpResponse
                    && ((HttpResponse) message).status().code() == responseStatus) {
                  owner.finish();
                }
                ctx.fireChannelRead(message);
              }

              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                observed.add(activeSpan());
              }

              @Override
              public void channelInactive(ChannelHandlerContext ctx) {
                observed.add(activeSpan());
              }
            });
    try {
      try (ContextScope scope = activateSpan(owner);
          ContextScope suppression = suppressNestedClientSpans(owner, parentContext)) {
        channel.writeOutbound(new DefaultHttpRequest(HTTP_1_1, GET, "/test"));
        channel.readOutbound();
      }
      if (streamChannel) {
        // Stream channels inherit the request context without a client-parent attribute.
        channel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).set(null);
      }
      channel.writeInbound(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE));
      assertSame(owner, AgentSpan.fromContext(channel.attr(CONTEXT_ATTRIBUTE_KEY).get()));
      channel.writeInbound(
          new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(responseStatus)));
      assertSame(parentContext, channel.attr(CONTEXT_ATTRIBUTE_KEY).get());
      channel.writeInbound(new DefaultHttpContent(Unpooled.buffer().writeByte(1)));
      channel.pipeline().fireExceptionCaught(new IOException("body failure"));
      channel.close();
      assertEquals(5, observed.size());
      assertSame(owner, observed.get(0));
      assertSame(owner, observed.get(1));
      for (int i = 2; i < observed.size(); i++) {
        assertSame(parent, observed.get(i));
      }
      assertFalse(((DDSpan) parent).isFinished());
    } finally {
      channel.finishAndReleaseAll();
      owner.finish();
      parent.finish();
    }
    assertTraces(
        trace(
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("webclient")));
  }

  @TableTest({
    "Scenario | Blocked",
    "allowed  | false  ",
    "blocked  | true   "
  })
  void checksTransportDestinationWithoutChangingOwnerMetadata(boolean blocked) {
    AtomicReference<String> checkedUrl = new AtomicReference<>();
    Subscription subscription =
        AgentTracer.get()
            .getSubscriptionService(RequestContextSlot.APPSEC)
            .registerCallback(
                EVENTS.httpClientRequest(),
                new BiFunction<RequestContext, HttpClientRequest, Flow<Void>>() {
                  @Override
                  public Flow<Void> apply(RequestContext context, HttpClientRequest request) {
                    checkedUrl.set(request.getUrl());
                    return blocked
                        ? new Flow.ResultFlow<Void>(null) {
                          @Override
                          public Action getAction() {
                            return new Action.RequestBlockingAction(403, BlockingContentType.AUTO);
                          }
                        }
                        : Flow.ResultFlow.empty();
                  }
                });
    AgentSpan owner =
        AgentTracer.get()
            .startSpan(
                "test", "webclient", new TagContext().withRequestContextDataAppSec(new Object()));
    owner.setTag("url.full", "http://original.example/original");
    EmbeddedChannel channel = new EmbeddedChannel(HttpClientRequestTracingHandler.INSTANCE);
    HttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, "/rewritten");
    request.headers().set("Host", "transport.example");
    try (ContextScope scope = activateSpan(owner);
        ContextScope suppression = suppressNestedClientSpans(owner, Context.root())) {
      if (blocked) {
        assertThrows(BlockingException.class, () -> channel.writeOutbound(request));
        assertNull(channel.readOutbound());
      } else {
        channel.writeOutbound(request);
        assertSame(request, channel.readOutbound());
      }
      assertEquals("http://transport.example/rewritten", checkedUrl.get());
      assertEquals("http://original.example/original", owner.getTag("url.full"));
    } finally {
      subscription.cancel();
      channel.finishAndReleaseAll();
      owner.finish();
    }
    assertTraces(trace(span().root().operationName("webclient")));
  }
}
