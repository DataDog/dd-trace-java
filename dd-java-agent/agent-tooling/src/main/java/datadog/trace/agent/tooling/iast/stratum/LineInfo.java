package datadog.trace.agent.tooling.iast.stratum;

/**
 * The line section associates line numbers in the output source with line numbers and source names
 * in the input source.
 *
 * <p>The format of the line section is the line section marker *L on a line by itself, followed by
 * the lines of LineInfo. Each LineInfo has the form:
 *
 * <p>InputStartLine # LineFileID , RepeatCount : OutputStartLine , OutputLineIncrement where all
 * but
 *
 * <p>InputStartLine : OutputStartLine are optional.
 *
 * <p><a
 * href="https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#stratumsection">...</a>
 */
public class LineInfo {
  private String fileId;

  final int inputStartLine;

  final int repeatCount;

  final int outputStartLine;

  final int outputLineIncrement;

  private FileInfo fileInfo;

  public LineInfo(
      String fileId,
      int inputStartLine,
      int repeatCount,
      int outputStartLine,
      int outputLineIncrement) {
    this.fileId = fileId;
    fileInfo = null;
    this.inputStartLine = inputStartLine;
    this.repeatCount = repeatCount;
    this.outputStartLine = outputStartLine;
    this.outputLineIncrement = outputLineIncrement;
  }

  public LineInfo(
      FileInfo fileInfo,
      int inputStartLine,
      int repeatCount,
      int outputStartLine,
      int outputLineIncrement) {
    this.fileInfo = fileInfo;
    this.inputStartLine = inputStartLine;
    this.repeatCount = repeatCount;
    this.outputStartLine = outputStartLine;
    this.outputLineIncrement = outputLineIncrement;
  }

  public String getFileId() {
    return fileId;
  }

  public int getInputStartLine() {
    return inputStartLine;
  }

  public int getRepeatCount() {
    return repeatCount;
  }

  public int getOutputStartLine() {
    return outputStartLine;
  }

  public int getOutputLineIncrement() {
    return outputLineIncrement;
  }

  public FileInfo getFileInfo() {
    return fileInfo;
  }

  public void setFileInfo(final FileInfo fileInfo) {
    this.fileInfo = fileInfo;
  }

  @Override
  public String toString() {
    return "LineInfo [fileId="
        + fileId
        + ", inputStartLine="
        + inputStartLine
        + ", repeatCount="
        + repeatCount
        + ", outputStartLine="
        + outputStartLine
        + ", outputLineIncrement="
        + outputLineIncrement
        + ", fileInfo="
        + fileInfo
        + "]\n";
  }
}
