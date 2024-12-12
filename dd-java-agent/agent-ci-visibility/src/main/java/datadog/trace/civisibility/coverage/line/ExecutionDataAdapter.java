package datadog.trace.civisibility.coverage.line;

import org.jacoco.core.data.ExecutionData;

public class ExecutionDataAdapter {
  private final long classId;
  private final String className;
  // Unbounded data structure that only exists within a single test span
  private final boolean[] probeActivations;

  public ExecutionDataAdapter(long classId, String className, int totalProbeCount) {
    this.classId = classId;
    this.className = className;
    this.probeActivations = new boolean[totalProbeCount];
  }

  public String getClassName() {
    return className;
  }

  void record(int probeId) {
    probeActivations[probeId] = true;
  }

  ExecutionDataAdapter merge(ExecutionDataAdapter other) {
    for (int i = 0; i < other.probeActivations.length; i++) {
      probeActivations[i] |= other.probeActivations[i];
    }
    return this;
  }

  ExecutionData toExecutionData() {
    return new ExecutionData(classId, className, probeActivations);
  }
}
