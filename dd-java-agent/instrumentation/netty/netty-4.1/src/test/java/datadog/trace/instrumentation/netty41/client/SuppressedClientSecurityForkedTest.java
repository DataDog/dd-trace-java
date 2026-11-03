package datadog.trace.instrumentation.netty41.client;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.suppressNestedClientSpans;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
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
import datadog.trace.test.junit.utils.config.WithConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.tabletest.junit.TableTest;

@WithConfig(key = "trace.otel.semantics.enabled", value = "true")
@WithConfig(key = "appsec.enabled", value = "true")
@WithConfig(key = "appsec.rasp.enabled", value = "true")
class SuppressedClientSecurityForkedTest extends AbstractInstrumentationTest {
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
        ContextScope suppression = suppressNestedClientSpans(owner)) {
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
