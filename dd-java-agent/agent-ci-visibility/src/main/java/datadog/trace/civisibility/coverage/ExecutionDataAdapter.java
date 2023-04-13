package datadog.trace.civisibility.coverage;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;

public class ExecutionDataAdapter {
  private final long classId;
  private final String className;
  // Unbounded data structure that only exists within a single test span
  private final Map<Long, BitSet> probeActivationsByThread = new HashMap<>();

  public ExecutionDataAdapter(long classId, String className) {
    this.classId = classId;
    this.className = className;
  }

  public String getClassName() {
    return className;
  }

  void record(int probeId) {
    probeActivationsByThread
        .computeIfAbsent(Thread.currentThread().getId(), (ignored) -> new BitSet())
        .set(probeId, true);
  }

  ExecutionData toExecutionData(int totalProbeCount) {
    boolean[] probes = new boolean[totalProbeCount];
    Iterator<BitSet> itr = probeActivationsByThread.values().iterator();
    BitSet probeActivations = itr.next();
    while (itr.hasNext()) {
      probeActivations.or(itr.next());
    }
    return new ExecutionData(classId, className, probes);
  }
}
