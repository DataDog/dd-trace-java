package testdog.trace.instrumentation.rxjava;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;
import rx.SuppressionTestWorker;

class AsyncSuppressionTest extends AbstractInstrumentationTest {
  @Test
  void suppressesPeriodicSchedulingButPreservesOrdinaryScheduling() {
    SuppressionTestWorker worker = new SuppressionTestWorker();
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      setAsyncPropagationEnabled(true);
      worker.schedulePeriodically(() -> {}, 1, 1, SECONDS).unsubscribe();
      assertFalse(worker.propagating);
      assertTrue(isAsyncPropagationEnabled());

      worker.schedule(() -> {}).unsubscribe();
      assertTrue(worker.propagating);
    } finally {
      parent.finish();
      worker.unsubscribe();
    }
    assertTraces(trace(span().root().operationName("parent")));
  }
}
