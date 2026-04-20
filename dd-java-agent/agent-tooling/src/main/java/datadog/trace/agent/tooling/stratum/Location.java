package datadog.trace.agent.tooling.stratum;

public class Location {
  private final FileInfo fileInfo;

  private final int lineNum;

  public Location(final FileInfo fileInfo, final int lineNum) {
    this.fileInfo = fileInfo;
    this.lineNum = lineNum;
  }

  public FileInfo getFileInfo() {
    return fileInfo;
  }

  public int getLineNum() {
    return lineNum;
  }
}
