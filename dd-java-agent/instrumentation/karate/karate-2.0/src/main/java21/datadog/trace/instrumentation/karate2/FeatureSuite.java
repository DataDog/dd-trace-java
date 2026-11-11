package datadog.trace.instrumentation.karate2;

import io.karatelabs.core.ScenarioRuntime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Lifecycle state of the test suite reported for a feature run. Scenarios of one feature run can
 * execute in parallel, so access is guarded by this object's monitor.
 */
public final class FeatureSuite {
  final Set<ScenarioRuntime> runningScenarios = Collections.newSetFromMap(new IdentityHashMap<>());
  boolean started;
  boolean featureFinished;

  /** Whether the suite was started for scenarios that run outside their feature's lifecycle. */
  boolean detached;
}
