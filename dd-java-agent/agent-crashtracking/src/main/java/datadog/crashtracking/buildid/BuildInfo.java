package datadog.crashtracking.buildid;

public class BuildInfo {
  public enum BuildIdType {
    GNU, // for ELF
    PDB // for DLL PE
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
