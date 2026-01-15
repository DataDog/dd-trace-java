package datadog.trace.agent.tooling.iast.stratum;

/**
 * The fileInfo describes the translated-source file names <a
 * href="https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#filesection">...</a>
 */
public class FileInfo {
  private final String fileId;

  private final String inputFileName;

  private final String inputFilePath;

  public FileInfo(String fileId, String inputFileName, String inputFilePath) {
    this.fileId = fileId;
    this.inputFileName = inputFileName;
    this.inputFilePath = inputFilePath;
  }

  public String getFileId() {
    return fileId;
  }

  public String getInputFileName() {
    return inputFileName;
  }

  public String getInputFilePath() {
    if (inputFilePath == null) {
      return inputFileName;
    }
    return inputFilePath;
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
