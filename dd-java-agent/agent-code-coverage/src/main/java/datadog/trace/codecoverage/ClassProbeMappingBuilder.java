package datadog.trace.codecoverage;

import java.util.BitSet;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

/**
 * Builds a {@link ClassProbeMapping} by delegating to JaCoCo's {@link Analyzer}. For each probe,
 * the class is analyzed with only that probe marked as executed; the covered lines are then
 * collected into the mapping.
 *
 * <p>This approach is O(probeCount) in the number of JaCoCo analysis passes. It is slower than a
 * single-pass approach but guaranteed to be correct, as it reuses JaCoCo's own analysis logic
 * including all filters and edge-case handling.
 */
final class ClassProbeMappingBuilder {

  static ClassProbeMapping build(
      long classId, String className, int probeCount, byte[] classBytes) {
    // Analyze with no probes to determine executable lines and source file
    IClassCoverage baseline = analyzeClass(classId, className, classBytes, null);
    if (baseline == null) {
      return new ClassProbeMapping(classId, className, null, new BitSet(), new int[probeCount][]);
    }

    String sourceFile = baseline.getSourceFileName();
    BitSet executableLines = collectLines(baseline, false);

    // For each probe, analyze with only that probe set to determine covered lines
    int[][] probeToLines = new int[probeCount][];
    for (int p = 0; p < probeCount; p++) {
      boolean[] probes = new boolean[probeCount];
      probes[p] = true;
      IClassCoverage cc = analyzeClass(classId, className, classBytes, probes);
      probeToLines[p] = (cc != null) ? bitSetToArray(collectLines(cc, true)) : new int[0];
    }

    return new ClassProbeMapping(classId, className, sourceFile, executableLines, probeToLines);
  }

  private static IClassCoverage analyzeClass(
      long classId, String className, byte[] classBytes, boolean[] probes) {
    ExecutionDataStore store = new ExecutionDataStore();
    if (probes != null) {
      store.put(new ExecutionData(classId, className, probes));
    }
    CoverageBuilder builder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(store, builder);
    try {
      analyzer.analyzeClass(classBytes, className);
    } catch (Exception e) {
      return null;
    }
    for (IClassCoverage cc : builder.getClasses()) {
      return cc;
    }
    return null;
  }

  private static BitSet collectLines(IClassCoverage cc, boolean coveredOnly) {
    BitSet lines = new BitSet();
    int first = cc.getFirstLine();
    int last = cc.getLastLine();
    if (first != ISourceNode.UNKNOWN_LINE) {
      for (int line = first; line <= last; line++) {
        int count =
            coveredOnly
                ? cc.getLine(line).getInstructionCounter().getCoveredCount()
                : cc.getLine(line).getInstructionCounter().getTotalCount();
        if (count > 0) {
          lines.set(line);
        }
      }
    }
    return lines;
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
