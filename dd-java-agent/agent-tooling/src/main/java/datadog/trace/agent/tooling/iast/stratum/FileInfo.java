package datadog.trace.agent.tooling.iast.stratum;

/**
 * The fileInfo describes the translated-source file names
 * https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#filesection
 */
public class FileInfo {
  private int fileId = -1;

  private String inputFileName;

  private String inputFilePath;

  public FileInfo() {}

  public FileInfo(final int fileId, final String inputFileName, final String inputFilePath) {
    this.fileId = fileId;
    this.inputFileName = inputFileName;
    this.inputFilePath = inputFilePath;
  }

  public int getFileId() {
    return fileId;
  }

  public void setFileId(final int fileId) {
    this.fileId = fileId;
  }

  public String getInputFileName() {
    return inputFileName;
  }

  public void setInputFileName(final String inputFileName) {
    this.inputFileName = inputFileName;
  }

  public String getInputFilePath() {
    if (inputFilePath == null) {
      return inputFileName;
    }
    return inputFilePath;
  }

  public void setInputFilePath(final String inputFilePath) {
    this.inputFilePath = inputFilePath;
  }

  @Override
  public String toString() {
    return "FileInfo [fileId="
        + fileId
        + ", inputFileName="
        + inputFileName
        + ", inputFilePath="
        + inputFilePath
        + "]";
  }
}