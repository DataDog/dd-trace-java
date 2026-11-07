package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

/** Isolates class-loading assertions from tests that may deliberately load coroutine helpers. */
class VirtualThreadScopeClassLoadingForkedTest extends AbstractInstrumentationTest {
  @Test
  void virtualThreadHandoffsDoNotLoadSharedScopeStacks() throws InterruptedException {
    AgentSpan parent = startSpan("test", "parent");
    AgentSpan[] active = new AgentSpan[2];
    try (ContextScope ignored = activateSpan(parent)) {
      Thread child =
          Thread.startVirtualThread(
              () -> {
                active[0] = activeSpan();
                LockSupport.parkNanos(MILLISECONDS.toNanos(1));
                active[1] = activeSpan();
              });
      assertTrue(child.join(Duration.ofSeconds(10)));
      assertSame(parent, active[0]);
      assertSame(parent, active[1]);
    } finally {
      parent.finish();
    }
    assertTraces(trace(span().root().operationName("parent")));

    for (Class<?> loaded : ByteBuddyAgent.getInstrumentation().getAllLoadedClasses()) {
      if (loaded != null) {
        String name = loaded.getName();
        assertFalse(
            "datadog.trace.core.scopemanager.SharedScopeStack".equals(name)
                || "datadog.trace.core.scopemanager.SharedScopeContext".equals(name),
            name);
      }
    }
  }
}
