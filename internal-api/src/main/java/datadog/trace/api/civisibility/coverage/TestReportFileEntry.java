package datadog.trace.api.civisibility.coverage;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class TestReportFileEntry {
  private final String sourceFileName;
  private final List<Segment> segments;

  public TestReportFileEntry(String sourceFileName, List<Segment> segments) {
    this.sourceFileName = sourceFileName;
    this.segments = segments;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public Collection<Segment> getSegments() {
    return segments;
  }

  @Override
  public String toString() {
    return "TestReportFileEntry{"
        + "sourceFileName='"
        + sourceFileName
        + "', lines=["
        + segments.stream().map(s -> String.valueOf(s.startLine)).collect(Collectors.joining(","))
        + "]}";
  }

  public static class Segment implements Comparable<Segment> {
    private final int startLine;
    private final int startColumn;
    private final int endLine;
    private final int endColumn;
    private final int numberOfExecutions;

    public Segment(
        int startLine, int startColumn, int endLine, int endColumn, int numberOfExecutions) {
      this.startLine = startLine;
      this.startColumn = startColumn;
      this.endLine = endLine;
      this.endColumn = endColumn;
      this.numberOfExecutions = numberOfExecutions;
    }

    public int getStartLine() {
      return startLine;
    }

    public int getStartColumn() {
      return startColumn;
    }

    public int getEndLine() {
      return endLine;
    }

    public int getEndColumn() {
      return endColumn;
    }

    public int getNumberOfExecutions() {
      return numberOfExecutions;
    }

    @Override
    public int compareTo(Segment segment) {
      return startLine - segment.startLine;
    }

    @Override
    public String toString() {
      return "Segment{"
          + "startLine="
          + startLine
          + ", startColumn="
          + startColumn
          + ", endLine="
          + endLine
          + ", endColumn="
          + endColumn
          + ", numberOfExecutions="
          + numberOfExecutions
          + '}';
    }
  }
}
