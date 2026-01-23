package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import datadog.crashtracking.buildid.BuildInfo;
import java.util.Objects;

public final class StackFrame {

  public final String file;
  public final Integer line;
  public final String function;

  @Json(name = "build_id")
  public final String buildId;

  @Json(name = "build_id_type")
  public final BuildInfo.BuildIdType buildIdType;

  @Json(name = "file_type")
  public final BuildInfo.FileType fileType;

  @Json(name = "relative_address")
  public String relativeAddress;

  public StackFrame(
      String file,
      Integer line,
      String function,
      String buildId,
      BuildInfo.BuildIdType buildIdType,
      BuildInfo.FileType fileType,
      String relativeAddress) {
    this.file = file;
    this.line = line;
    this.function = function;
    this.buildId = buildId;
    this.buildIdType = buildIdType;
    this.fileType = fileType;
    this.relativeAddress = relativeAddress;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StackFrame that = (StackFrame) o;
    return Objects.equals(file, that.file)
        && Objects.equals(line, that.line)
        && Objects.equals(function, that.function)
        && Objects.equals(buildId, that.buildId)
        && buildIdType == that.buildIdType
        && fileType == that.fileType
        && Objects.equals(relativeAddress, that.relativeAddress);
  }

  @Override
  public int hashCode() {
    return Objects.hash(file, line, function, buildId, buildIdType, fileType, relativeAddress);
  }
}
