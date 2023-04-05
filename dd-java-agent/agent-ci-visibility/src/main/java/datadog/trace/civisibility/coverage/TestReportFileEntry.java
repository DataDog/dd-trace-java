package datadog.trace.civisibility.coverage;

import java.util.*;
import java.util.stream.Collectors;

class TestReportFileEntry {
  private final Set<Segment> segments = new TreeSet<>();
  private String sourceFileName;

  public TestReportFileEntry setSourceFileName(String packageName, String sourceFileName) {
    this.sourceFileName = packageName + "/" + sourceFileName;
    return this;
  }

  public TestReportFileEntry incrementLine(
      int lineNumber, int instructionCounter, int branchCounter) {
    segments.add(new Segment(lineNumber, -1, lineNumber, -1, instructionCounter + branchCounter));
    return this;
  }

  public boolean hasSegments() {
    return !segments.isEmpty();
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
