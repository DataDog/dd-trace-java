package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores;
import datadog.trace.bootstrap.FieldBackedContextStores;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

class PollerContextForkedTest extends AbstractInstrumentationTest {
  @Test
  @EnabledForJreRange(min = JRE.JAVA_22)
  void firstPollerInitializationDoesNotRetainRequest() throws Exception {
    // Set these before Poller.<clinit>; changing them afterward cannot reconfigure its pollers.
    // The fork isolates the properties and poller initialization from other tests.
    System.setProperty("jdk.pollerMode", "VTHREAD_POLLERS");
    System.setProperty("jdk.readPollers", "1");
    System.setProperty("jdk.writePollers", "1");
    assertFalse(GlobalIgnores.isIgnored("sun.nio.ch.Poller$Pollers", false));
    assertTrue(GlobalIgnores.isIgnored("sun.nio.ch.Poller", false));
    AgentSpan parent = startSpan("test", "poller-owner");
    try (ContextScope ignored = activateSpan(parent)) {
      Class<?> poller = Class.forName("sun.nio.ch.Poller");
      Object pollers = field(poller, "POLLERS").get(null);
      Object executor = field(pollers.getClass(), "executor").get(pollers);
      @SuppressWarnings("unchecked")
      Set<Thread> threads = (Set<Thread>) field(executor.getClass(), "threads").get(executor);
      assertEquals(2, threads.size(), "one read poller and one write poller must be running");
      for (Thread thread : threads) {
        assertTrue(thread.isVirtual());
        assertNull(
            virtualThreadState(thread), "poller must retain neither context nor continuation");
      }
      assertSame(parent, activeSpan(), "poller startup must restore the caller context");
    } finally {
      parent.finish();
    }
    assertTraces(trace(span().root().operationName("poller-owner")));
  }

  static Object virtualThreadState(Thread thread) {
    int storeId =
        FieldBackedContextStores.getContextStoreId(
            "java.lang.VirtualThread",
            "datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState");
    return FieldBackedContextStores.getContextStore(storeId).get(thread);
  }

  static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
