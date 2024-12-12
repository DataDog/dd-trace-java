package datadog.trace.civisibility.coverage.line;

import java.util.BitSet;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageVisitor;

public class SourceAnalyzer implements ICoverageVisitor {

  private final BitSet coveredLines;

  public SourceAnalyzer(BitSet coveredLines) {
    this.coveredLines = coveredLines;
  }

  @Override
  public void visitCoverage(IClassCoverage coverage) {
    if (coverage.isNoMatch()) {
      return;
    }

    int firstLine = coverage.getFirstLine();
    if (firstLine == -1) {
      return;
    }

    int lastLine = coverage.getLastLine();

    for (int line = firstLine; line <= lastLine; line++) {
      if (coverage.getLine(line).getStatus() >= ICounter.FULLY_COVERED) {
        coveredLines.set(line);
      }
    }
  }
}
