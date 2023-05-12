package datadog.trace.api.civisibility.coverage;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class TestReportFileEntry {
  private final Set<Segment> segments = new TreeSet<>();
  private final String sourceFileName;

  public TestReportFileEntry(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public TestReportFileEntry incrementLine(
      int lineNumber, int instructionCounter, int branchCounter) {
    segments.add(new Segment(lineNumber, -1, lineNumber, -1, instructionCounter + branchCounter));
    return this;
  }

  public boolean hasSegments() {
    return !segments.isEmpty();
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public Set<Segment> getSegments() {
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
    public boolean equals(Object obj) {
      if (!(obj instanceof Segment)) {
        return false;
      }
      return startLine == ((Segment) obj).startLine;
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
