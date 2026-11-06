package datadog.trace.instrumentation.netty41.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import org.junit.jupiter.api.Test;

/** Requests sent under a Cosmos request span get no HTTP client span of their own. */
class SuppressedClientRequestTest extends AbstractInstrumentationTest {

  @Test
  void suppressedRequestOnReusedConnectionLeavesPreviousParentAlone() {
    EmbeddedChannel channel =
        new EmbeddedChannel(
            HttpClientRequestTracingHandler.INSTANCE, HttpClientResponseTracingHandler.INSTANCE);

    // a traced request, under an application span, leaves its state on the connection
    AgentSpan application = startSpan("test", "application");
    try (ContextScope ignored = activateSpan(application)) {
      channel.writeOutbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/traced"));
      channel.writeInbound(new DefaultFullHttpResponse(HTTP_1_1, OK));
    }

    // a Cosmos request then reuses the connection
    AgentSpan cosmos = startSpan("azure-cosmos", "cosmosdb.query");
    cosmos.setSpanType("cosmosdb");
    try (ContextScope ignored = activateSpan(cosmos)) {
      channel.writeOutbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/cosmos"));
      channel.writeInbound(new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND));
    }

    DDSpan applicationSpan = (DDSpan) application;
    assertFalse(applicationSpan.isFinished(), "application span finished by the Cosmos response");
    assertNull(applicationSpan.getTag(Tags.HTTP_STATUS), "application span tagged");

    cosmos.finish();
    application.finish();
    channel.finishAndReleaseAll();
  }
}
