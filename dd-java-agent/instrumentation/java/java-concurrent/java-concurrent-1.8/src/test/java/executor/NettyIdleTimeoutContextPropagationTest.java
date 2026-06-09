package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Test;

class NettyIdleTimeoutContextPropagationTest extends AbstractInstrumentationTest {

  @Test
  void testIdleStateHandlerDoesNotHoldTraceOpen() {
    assertTimeoutHandlerDoesNotHoldTraceOpen(new IdleStateHandler(60, 60, 60));
  }

  @Test
  void testReadTimeoutHandlerDoesNotHoldTraceOpen() {
    // ReadTimeoutHandler inherits IdleStateHandler#schedule, so the same suppression must apply.
    assertTimeoutHandlerDoesNotHoldTraceOpen(new ReadTimeoutHandler(60));
  }

  private void assertTimeoutHandlerDoesNotHoldTraceOpen(io.netty.channel.ChannelHandler handler) {
    // An EmbeddedChannel is active and registered, so adding the handler triggers
    // IdleStateHandler#schedule for its long (60s) idle timers.
    EmbeddedChannel channel = new EmbeddedChannel();
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope ignored = activateSpan(parent)) {
      channel.pipeline().addLast(handler);
    } finally {
      parent.finish();
    }

    // If the idle timers had captured the active request continuation, the trace would stay open
    // until they fire (60s) and this assertion would time out. Suppression lets it report promptly.
    assertTraces(trace(span().root().operationName("parent")));

    channel.finishAndReleaseAll();
  }
}
