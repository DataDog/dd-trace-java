package datadog.trace.instrumentation.kotlin.coroutines;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import kotlin.coroutines.EmptyCoroutineContext;
import org.junit.jupiter.api.Test;

class DatadogThreadContextElementHandoffForkedTest {
  @Test
  void resumptionBeforeRestorationPreservesTheEntireScopeStack() throws Exception {
    AgentTracer.installLegacyContextManager();
    Properties properties = new Properties();
    properties.setProperty("dd.trace.scope.strict.mode", "true");
    CoreTracer tracer =
        CoreTracer.builder()
            .withProperties(properties)
            .strictTraceWrites(true)
            .writer(new ListWriter())
            .build();
    AgentTracer.forceRegister(tracer);
    ExecutorService second = newSingleThreadExecutor();
    ExecutorService third = newSingleThreadExecutor();
    DatadogThreadContextElement element =
        (DatadogThreadContextElement)
            DatadogThreadContextElement.addDatadogElement(EmptyCoroutineContext.INSTANCE);
    Context firstPrevious = element.updateThreadContext(element);
    AgentSpan root = tracer.startSpan("test", "root");
    ContextScope rootScope = tracer.activateManualSpan(root);
    AgentSpan child = tracer.startSpan("test", "child");
    ContextScope childScope = tracer.activateManualSpan(child);
    AgentSpan leaf = tracer.startSpan("test", "leaf");
    ContextScope leafScope = tracer.activateManualSpan(leaf);
    try {
      // Kotlin permits the next update before the previous restore has even started.
      Context secondPrevious =
          second
              .submit(
                  () -> {
                    Context previous = element.updateThreadContext(element);
                    assertSame(leaf, tracer.activeSpan());
                    leafScope.close();
                    assertSame(child, tracer.activeSpan());
                    childScope.close();
                    assertSame(root, tracer.activeSpan());
                    return previous;
                  })
              .get(10, SECONDS);
      element.restoreThreadContext(element, firstPrevious);
      assertNull(tracer.activeSpan(), "The first worker must recover its previous context");

      third
          .submit(
              () -> {
                Context previous = element.updateThreadContext(element);
                try {
                  assertSame(
                      root, tracer.activeSpan(), "A late restore must not overwrite progress");
                  rootScope.close();
                  assertNull(tracer.activeSpan());
                } finally {
                  element.restoreThreadContext(element, previous);
                }
              })
          .get(10, SECONDS);
      second
          .submit(
              () -> {
                element.restoreThreadContext(element, secondPrevious);
                assertNull(
                    tracer.activeSpan(), "The second worker must recover its previous context");
                Context previous = element.updateThreadContext(element);
                try {
                  assertNull(tracer.activeSpan(), "Late restores must not resurrect closed scopes");
                } finally {
                  element.restoreThreadContext(element, previous);
                }
              })
          .get(10, SECONDS);
    } finally {
      second.shutdownNow();
      third.shutdownNow();
      leaf.finish();
      child.finish();
      root.finish();
      tracer.close();
    }
  }
}
