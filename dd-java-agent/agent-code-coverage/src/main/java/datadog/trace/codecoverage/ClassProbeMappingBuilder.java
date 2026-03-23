package datadog.trace.codecoverage;

import java.io.IOException;
import java.util.BitSet;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

/**
 * Builds a {@link ClassProbeMapping} by running JaCoCo's {@link Analyzer} with controlled probe
 * configurations.
 *
 * <p>For a class with N probes, this runs N+1 analyzer passes: one with all probes false (to
 * determine executable lines), then one per probe (to determine which lines each probe covers).
 */
final class ClassProbeMappingBuilder {

  /**
   * Builds a {@link ClassProbeMapping} from raw class bytes.
   *
   * @param classId CRC64 of the class bytes
   * @param className VM class name (e.g. "com/example/MyClass")
   * @param probeCount number of probes in this class
   * @param classBytes original class file bytes (must match classId)
   * @return the mapping, never null
   * @throws IOException if the class bytes cannot be analyzed
   */
  static ClassProbeMapping build(
      long classId, String className, int probeCount, byte[] classBytes) throws IOException {

    // 1. Get executable lines (analyze with all probes false)
    BitSet executableLines = new BitSet();
    String sourceFile = null;
    {
      ExecutionDataStore store = new ExecutionDataStore();
      store.put(new ExecutionData(classId, className, probeCount)); // all false
      CoverageBuilder builder = new CoverageBuilder();
      Analyzer analyzer = new Analyzer(store, builder);
      analyzer.analyzeClass(classBytes, className);
      for (IClassCoverage cc : builder.getClasses()) {
        if (cc.getSourceFileName() != null) {
          sourceFile = cc.getPackageName() + "/" + cc.getSourceFileName();
        }
        for (int line = cc.getFirstLine(); line <= cc.getLastLine(); line++) {
          if (cc.getLine(line).getStatus() != ICounter.EMPTY) {
            executableLines.set(line);
          }
        }
      }
    }

    if (sourceFile == null) {
      // No source info -- create a mapping with no lines
      return new ClassProbeMapping(classId, null, executableLines, new int[probeCount][]);
    }

    // 2. Build per-probe line mapping
    int[][] probeToLines = new int[probeCount][];
    for (int probeId = 0; probeId < probeCount; probeId++) {
      boolean[] probes = new boolean[probeCount];
      probes[probeId] = true;

      ExecutionDataStore store = new ExecutionDataStore();
      store.put(new ExecutionData(classId, className, probes));
      CoverageBuilder builder = new CoverageBuilder();
      Analyzer analyzer = new Analyzer(store, builder);
      analyzer.analyzeClass(classBytes, className);

      // Collect covered lines for this probe
      BitSet coveredByProbe = new BitSet();
      for (IClassCoverage cc : builder.getClasses()) {
        for (int line = cc.getFirstLine(); line <= cc.getLastLine(); line++) {
          int status = cc.getLine(line).getStatus();
          if (status == ICounter.PARTLY_COVERED || status == ICounter.FULLY_COVERED) {
            coveredByProbe.set(line);
          }
        }
      }

      // Convert BitSet to sorted int[]
      probeToLines[probeId] = bitSetToArray(coveredByProbe);
    }

    return new ClassProbeMapping(classId, sourceFile, executableLines, probeToLines);
  }

  private static int[] bitSetToArray(BitSet bs) {
    int[] result = new int[bs.cardinality()];
    int idx = 0;
    for (int bit = bs.nextSetBit(0); bit >= 0; bit = bs.nextSetBit(bit + 1)) {
      result[idx++] = bit;
    }
    return result;
  }
}
