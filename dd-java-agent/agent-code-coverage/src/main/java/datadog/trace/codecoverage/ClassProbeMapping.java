package datadog.trace.codecoverage;

import java.util.BitSet;

/**
 * Cached mapping from probe IDs to source lines for a single class. Built once per class version
 * (identified by CRC64) and reused across collection cycles.
 */
final class ClassProbeMapping {
  final long classId;
  final String sourceFile; // "package/SourceFile.java"
  final BitSet executableLines;
  final int[][] probeToLines; // probeToLines[probeId] = sorted line numbers

  ClassProbeMapping(
      long classId, String sourceFile, BitSet executableLines, int[][] probeToLines) {
    this.classId = classId;
    this.sourceFile = sourceFile;
    this.executableLines = executableLines;
    this.probeToLines = probeToLines;
  }
}
