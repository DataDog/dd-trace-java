package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import datadog.environment.SystemProperties;
import java.util.Objects;

public final class OSInfo {
  public final String architecture;
  public final String bitness;

  @Json(name = "os_type")
  public final String osType;

  public final SemanticVersion version;

  public OSInfo(String architecture, String bitness, String osType, SemanticVersion version) {
    this.architecture = architecture;
    this.bitness = bitness;
    this.osType = osType;
    this.version = version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OSInfo osInfo = (OSInfo) o;
    return Objects.equals(architecture, osInfo.architecture)
        && Objects.equals(bitness, osInfo.bitness)
        && Objects.equals(osType, osInfo.osType)
        && Objects.equals(version, osInfo.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(architecture, bitness, osType, version);
  }

  public static OSInfo current() {
    return new OSInfo(
        SystemProperties.get("os.arch"),
        SystemProperties.get("sun.arch.data.model"),
        SystemProperties.get("os.name"),
        SemanticVersion.of(SystemProperties.get("os.version")));
  }
}
