package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.rxjava.enabled", value = "false")
@WithConfig(key = "trace.java_concurrent.enabled", value = "true")
class AsyncSuppressionForkedTest extends AbstractInstrumentationTest {

  @Test
  void sentinelInitializationDoesNotRetainTraceWhenRxJavaInstrumentationIsDisabled()
      throws ClassNotFoundException {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      setAsyncPropagationEnabled(true);
      // First initialization must happen under the request scope; sentinels are never executed.
      Class.forName("io.reactivex.rxjava3.internal.schedulers.AbstractDirectTask");
      assertTrue(isAsyncPropagationEnabled());
    } finally {
      parent.finish();
    }
    assertTraces(trace(span().root().operationName("parent")));
  }
}
