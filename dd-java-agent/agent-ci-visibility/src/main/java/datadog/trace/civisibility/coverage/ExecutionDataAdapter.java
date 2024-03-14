package datadog.trace.civisibility.coverage;

import java.util.BitSet;
import org.jacoco.core.data.ExecutionData;

public class ExecutionDataAdapter {
  private final long classId;
  private final String className;
  // Unbounded data structure that only exists within a single test span
  private final BitSet probeActivations = new BitSet();

  public ExecutionDataAdapter(long classId, String className) {
    this.classId = classId;
    this.className = className;
  }

  public String getClassName() {
    return className;
  }

  void record(int probeId) {
    probeActivations.set(probeId, true);
  }

  ExecutionDataAdapter merge(ExecutionDataAdapter other) {
    probeActivations.or(other.probeActivations);
    return this;
  }

  ExecutionData toExecutionData(int totalProbeCount) {
    boolean[] probes = new boolean[totalProbeCount];
    probeActivations.stream().forEach(p -> probes[p] = true);
    return new ExecutionData(classId, className, probes);
  }
}
