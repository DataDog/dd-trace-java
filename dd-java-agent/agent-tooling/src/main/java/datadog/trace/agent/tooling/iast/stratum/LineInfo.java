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
 * <p>https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#stratumsection
 */
public class LineInfo implements Cloneable {
  private int fileId = -1;

  int inputStartLine;

  int repeatCount;

  int outputStartLine;

  int outputLineIncrement;

  private FileInfo fileInfo;

  public LineInfo(
      final int fileId,
      final int inputStartLine,
      final int repeatCount,
      final int outputStartLine,
      final int outputLineIncrement) {
    this.fileId = fileId;
    fileInfo = null;
    this.inputStartLine = inputStartLine;
    this.repeatCount = repeatCount;
    this.outputStartLine = outputStartLine;
    this.outputLineIncrement = outputLineIncrement;
  }

  public LineInfo(
      final FileInfo fileInfo,
      final int inputStartLine,
      final int repeatCount,
      final int outputStartLine,
      final int outputLineIncrement) {
    fileId = -1;
    this.fileInfo = fileInfo;
    this.inputStartLine = inputStartLine;
    this.repeatCount = repeatCount;
    this.outputStartLine = outputStartLine;
    this.outputLineIncrement = outputLineIncrement;
  }

  @Override
  public Object clone() {
    LineInfo lineInfo =
        new LineInfo(fileId, inputStartLine, repeatCount, outputStartLine, outputLineIncrement);

    lineInfo.setFileInfo(fileInfo);
    return lineInfo;
  }

  public int getFileId() {
    return fileId;
  }

  public void setFileId(final int fileId) {
    this.fileId = fileId;
  }

  public int resolveFileId() {
    if (fileInfo != null) {
      fileId = fileInfo.getFileId();
    }
    return fileId;
  }

  public int getInputStartLine() {
    return inputStartLine;
  }

  public void setInputStartLine(final int inputStartLine) {
    this.inputStartLine = inputStartLine;
  }

  public int getRepeatCount() {
    return repeatCount;
  }

  public void setRepeatCount(final int repeatCount) {
    this.repeatCount = repeatCount;
  }

  public int getOutputStartLine() {
    return outputStartLine;
  }

  public void setOutputStartLine(final int outputStartLine) {
    this.outputStartLine = outputStartLine;
  }

  public int getOutputLineIncrement() {
    return outputLineIncrement;
  }

  public void setOutputLineIncrement(final int outputLineIncrement) {
    this.outputLineIncrement = outputLineIncrement;
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
