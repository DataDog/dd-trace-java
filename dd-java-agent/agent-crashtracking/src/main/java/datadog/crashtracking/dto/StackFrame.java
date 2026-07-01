package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import datadog.crashtracking.buildid.BuildInfo;
import java.util.Objects;

public final class StackFrame {

  public final String path;
  public final Integer line;
  public final String function;

  @Json(name = "type")
  public final String frameType;

  @Json(name = "build_id")
  public final String buildId;

  @Json(name = "build_id_type")
  public final BuildInfo.BuildIdType buildIdType;

  @Json(name = "file_type")
  public final BuildInfo.FileType fileType;

  @Json(name = "ip")
  public final String ip;

  @Json(name = "symbol_address")
  public final String symbolAddress;

  @Json(name = "relative_address")
  public String relativeAddress;

  public StackFrame(
      String path,
      Integer line,
      String function,
      String frameType,
      String buildId,
      BuildInfo.BuildIdType buildIdType,
      BuildInfo.FileType fileType,
      String ip,
      String symbolAddress,
      String relativeAddress) {
    this.path = path;
    this.line = line;
    this.function = function;
    this.frameType = frameType;
    this.buildId = buildId;
    this.buildIdType = buildIdType;
    this.fileType = fileType;
    this.ip = ip;
    this.symbolAddress = symbolAddress;
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
    return Objects.equals(path, that.path)
        && Objects.equals(line, that.line)
        && Objects.equals(function, that.function)
        && Objects.equals(frameType, that.frameType)
        && Objects.equals(buildId, that.buildId)
        && buildIdType == that.buildIdType
        && fileType == that.fileType
        && Objects.equals(ip, that.ip)
        && Objects.equals(symbolAddress, that.symbolAddress)
        && Objects.equals(relativeAddress, that.relativeAddress);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        path,
        line,
        function,
        frameType,
        buildId,
        buildIdType,
        fileType,
        ip,
        symbolAddress,
        relativeAddress);
  }
}
