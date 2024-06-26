package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import java.util.List;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageVisitor;

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
    if (firstLine == -1) {
      return;
    }

    int lastLine = coverage.getLastLine();
    int line = firstLine;
    while (line <= lastLine) {
      if (coverage.getLine(line).getStatus() >= ICounter.FULLY_COVERED) {
        int start = line++;
        while (line <= lastLine && coverage.getLine(line).getStatus() >= ICounter.FULLY_COVERED) {
          line++;
        }
        segments.add(new TestReportFileEntry.Segment(start, -1, line - 1, -1, -1));

      } else {
        line++;
      }
    }
  }
}
