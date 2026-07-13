package datadog.trace.civisibility.coverage.line;

import org.jacoco.core.data.ExecutionData;

public class ExecutionDataAdapter {
  private final long classId;
  private final String className;
  // Jacoco's shared probe array for the class, used to back-fill aggregate coverage at report time
  private final boolean[] jacocoProbes;
  // Per-test probe array that Jacoco's instrumentation writes into while a test is running
  private final boolean[] probeActivations;

  public ExecutionDataAdapter(long classId, String className, boolean[] jacocoProbes) {
    this.classId = classId;
    this.className = className;
    this.jacocoProbes = jacocoProbes;
    this.probeActivations = new boolean[jacocoProbes.length];
  }

  public String getClassName() {
    return className;
  }

  boolean[] getProbeActivations() {
    return probeActivations;
  }

  ExecutionDataAdapter merge(ExecutionDataAdapter other) {
    for (int i = 0; i < other.probeActivations.length; i++) {
      probeActivations[i] |= other.probeActivations[i];
    }
    return this;
  }

  /**
   * Folds the per-test coverage back into Jacoco's shared probe array. Jacoco's aggregate coverage
   * (used for total module/session coverage percentage and report uploads) no longer sees probes
   * recorded into the per-test array directly, so they are OR-ed back here at report time. The
   * write is monotonic (bits are only ever set), so concurrent back-fills from multiple tests are
   * safe.
   *
   * <p>The per-test array is allocated when a method of the class is entered (so the probe array
   * can be swapped in), which can happen even if no probe ends up firing (e.g. the method throws
   * before reaching its first probe). Returning whether any probe was actually covered lets the
   * caller skip such classes and avoid emitting empty coverage entries.
   *
   * @return {@code true} if at least one probe was covered by the test
   */
  boolean mergeIntoJacocoProbes() {
    boolean covered = false;
    for (int i = 0; i < probeActivations.length; i++) {
      if (probeActivations[i]) {
        jacocoProbes[i] = true;
        covered = true;
      }
    }
    return covered;
  }

  ExecutionData toExecutionData() {
    return new ExecutionData(classId, className, probeActivations);
  }
}
