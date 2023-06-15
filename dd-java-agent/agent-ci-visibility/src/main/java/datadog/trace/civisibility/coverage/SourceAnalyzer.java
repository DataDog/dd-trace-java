package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import java.util.List;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageVisitor;
import org.jacoco.core.analysis.ILine;

public class SourceAnalyzer implements ICoverageVisitor {

  private final List<TestReportFileEntry.Segment> segments;

  public SourceAnalyzer(List<TestReportFileEntry.Segment> segments) {
    this.segments = segments;
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
          segments.add(new TestReportFileEntry.Segment(i, -1, i, -1, -1));
        }
      }
    }
  }
}
