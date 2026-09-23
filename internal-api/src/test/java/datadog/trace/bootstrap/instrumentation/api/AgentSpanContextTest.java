package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class AgentSpanContextTest {
  @Test
  void extractedContextReturnsItselfWhenSamplingPriorityCannotBeReplaced() {
    AgentSpanContext.Extracted context = NoopSpanContext.INSTANCE;

    assertSame(context, context.withSamplingPriority(UNSET));
  }
}
