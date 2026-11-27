package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * A long-lived publisher (like reactor-netty's pool allocator) that is subscribed once under a span
 * must not hand that span to a later subscription made on the same thread without one.
 */
class StaleHandoffContextTest extends AbstractInstrumentationTest {

  private static final long NO_SPAN = 0L;
  private static final long TIMEOUT_SECONDS = 10;

  // One thread, so both subscriptions land on the thread that made the thread-confined deposit,
  // as on a single-vCPU host where a scheduler has a single thread.
  private final ExecutorService singleThread = Executors.newSingleThreadExecutor();

  @AfterEach
  void shutdownExecutor() throws InterruptedException {
    singleThread.shutdownNow();
    singleThread.awaitTermination(TIMEOUT_SECONDS, SECONDS);
  }

  @Test
  void laterSubscriptionWithoutSpanDoesNotAdoptEarlierSubscriptionSpan() throws Exception {
    final AtomicLong spanSeenBySubscription = new AtomicLong();
    final Mono<String> longLived =
        Mono.create(sink -> {
          spanSeenBySubscription.set(activeSpanId());
          sink.success("done");
        });

    final long firstSpanId =
        singleThread
            .submit(() -> {
              final AgentSpan firstSpan = startSpan("test", "first.subscription");
              try (ContextScope scope = activateSpan(firstSpan)) {
                longLived.subscribe();
              } finally {
                firstSpan.finish();
              }
              return firstSpan.getSpanId();
            })
            .get(TIMEOUT_SECONDS, SECONDS);
    assertEquals(firstSpanId, spanSeenBySubscription.get());

    spanSeenBySubscription.set(NO_SPAN);
    singleThread
        .submit(() -> {
          assertNull(activeSpan());
          longLived.subscribe();
        })
        .get(TIMEOUT_SECONDS, SECONDS);
    assertEquals(
        NO_SPAN, spanSeenBySubscription.get(), "second subscription adopted the first one's span");

    assertTraces(trace(span().root().operationName("first.subscription")));
  }

  private static long activeSpanId() {
    return activeSpan() == null ? NO_SPAN : activeSpan().getSpanId();
  }
}
