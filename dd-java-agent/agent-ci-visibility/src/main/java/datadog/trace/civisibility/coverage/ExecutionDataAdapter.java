package datadog.trace.civisibility.coverage;

import java.util.BitSet;
import org.jacoco.core.data.ExecutionData;

public class ExecutionDataAdapter {
  private final long classId;
  private final String className;
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

  ExecutionData toExecutionData(int totalProbeCount) {
    boolean[] probes = new boolean[totalProbeCount];
    probeActivations.stream().forEach(p -> probes[p] = true);
    return new ExecutionData(classId, className, probes);
  }
}
