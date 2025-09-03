package datadog.trace.civisibility.coverage.report;

import java.util.BitSet;

public final class LinesCoverage {
  public final BitSet coveredLines = new BitSet();
  public final BitSet executableLines = new BitSet();
}
