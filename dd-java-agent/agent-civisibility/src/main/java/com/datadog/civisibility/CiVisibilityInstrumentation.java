package com.datadog.civisibility;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.Set;

public abstract class CiVisibilityInstrumentation extends Instrumenter.Default {

  public CiVisibilityInstrumentation(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public final boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.CIVISIBILITY);
  }
}
