package datadog.trace.coverage;

import java.util.BitSet;

public final class LinesCoverage {
  public final BitSet coveredLines = new BitSet();
  public final BitSet executableLines = new BitSet();
}
