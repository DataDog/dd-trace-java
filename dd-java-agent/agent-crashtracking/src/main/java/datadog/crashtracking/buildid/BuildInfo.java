package datadog.crashtracking.buildid;

public class BuildInfo {
  public enum BuildIdType {
    SHA1, // ELF
    PE // WIN
  }

  public enum FileType {
    ELF,
    PE,
  }

  public final String buildId;
  public final BuildIdType buildIdType;
  public final FileType fileType;

  public BuildInfo(final String buildId, final BuildIdType buildIdType, final FileType fileType) {
    this.buildId = buildId;
    this.buildIdType = buildIdType;
    this.fileType = fileType;
  }
}
