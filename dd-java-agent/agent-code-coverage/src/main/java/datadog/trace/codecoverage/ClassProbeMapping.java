package datadog.trace.codecoverage;

import java.util.BitSet;

/**
 * Cached mapping from probe IDs to source lines for a single class. Built once per class version
 * (identified by CRC64) and reused across collection cycles.
 */
final class ClassProbeMapping {
  final long classId;
  final String className; // "com/example/MyClass"
  final String sourceFile; // "SourceFile.java"
  final BitSet executableLines;
  final int[][] probeToLines; // probeToLines[probeId] = sorted line numbers

  ClassProbeMapping(
      long classId,
      String className,
      String sourceFile,
      BitSet executableLines,
      int[][] probeToLines) {
    this.classId = classId;
    this.className = className;
    this.sourceFile = sourceFile;
    this.executableLines = executableLines;
    this.probeToLines = probeToLines;
  }
}
