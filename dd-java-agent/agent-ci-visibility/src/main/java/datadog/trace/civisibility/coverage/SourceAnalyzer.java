package datadog.trace.civisibility.coverage;

import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageVisitor;
import org.jacoco.core.analysis.ILine;

public class SourceAnalyzer implements ICoverageVisitor {

  private final TestReportFileEntry fileEntry;

  public SourceAnalyzer(TestReportFileEntry fileEntry) {
    this.fileEntry = fileEntry;
  }

  @Override
  public void visitCoverage(IClassCoverage coverage) {
    if (coverage.isNoMatch()) {
      return;
    }

    int firstLine = coverage.getFirstLine();
    if (firstLine != -1) {
      int lastLine = coverage.getLastLine();
      for (int i = firstLine; i <= lastLine; i++) {
        ILine line = coverage.getLine(i);
        if (line.getStatus() >= ICounter.FULLY_COVERED) {
          fileEntry.incrementLine(
              i,
              line.getInstructionCounter().getCoveredCount(),
              line.getBranchCounter().getCoveredCount());
        }
      }
    }
  }
}
