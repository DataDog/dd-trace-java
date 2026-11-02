package datadog.crashtracking.buildid;

public class BuildInfo {
  public enum BuildIdType {
    // for ELF
    GNU,
    // for DLL PE
    PDB
  }

  public enum FileType {
    ELF,
    PE
  }

  static final BuildInfo EMPTY = new BuildInfo(null, null, null);
  public final String buildId;
  public final BuildIdType buildIdType;
  public final FileType fileType;

  public BuildInfo(final String buildId, final BuildIdType buildIdType, final FileType fileType) {
    this.buildId = buildId;
    this.buildIdType = buildIdType;
    this.fileType = fileType;
  }
}
