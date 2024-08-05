package datadog.trace.api.civisibility.coverage;

import java.util.BitSet;
import javax.annotation.Nullable;

public class TestReportFileEntry {
  private final String sourceFileName;
  private final @Nullable BitSet coveredLines;

  public TestReportFileEntry(String sourceFileName, @Nullable BitSet coveredLines) {
    this.sourceFileName = sourceFileName;
    this.coveredLines = coveredLines;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  @Nullable
  public BitSet getCoveredLines() {
    return coveredLines;
  }

  @Override
  public String toString() {
    return "TestReportFileEntry{"
        + "sourceFileName='"
        + sourceFileName
        + "', lines=["
        + coveredLines
        + "]}";
  }
}
