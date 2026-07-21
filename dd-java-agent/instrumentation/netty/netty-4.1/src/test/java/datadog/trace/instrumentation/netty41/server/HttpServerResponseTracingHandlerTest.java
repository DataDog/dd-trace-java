package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
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
}
