package datadog.trace.agent.test.base;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.Set;

/** Skeleton single-class test instrumentation. */
public abstract class TestInstrumentation
    implements Instrumenter, Instrumenter.ForSingleType, Instrumenter.HasAdvice {
  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true; // always on for testing purposes
  }

  @Override
  public void instrument(TransformerBuilder transformerBuilder) {
    transformerBuilder.applyInstrumentation(this);
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {}
}
