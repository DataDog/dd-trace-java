package datadog.trace.api.civisibility.config;

import java.util.Map;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

public final class Configurations {
  private final String osPlatform;
  private final String osArchitecture;
  private final String osVersion;
  private final String runtimeName;
  private final String runtimeVersion;
  private final String runtimeVendor;
  private final String runtimeArchitecture;
  private final String testBundle;
  private final Map<String, String> custom;

  public Configurations(
      String osPlatform,
      String osArchitecture,
      String osVersion,
      String runtimeName,
      String runtimeVersion,
      String runtimeVendor,
      String runtimeArchitecture,
      String testBundle,
      Map<String, String> custom) {
    this.osPlatform = osPlatform;
    this.osArchitecture = osArchitecture;
    this.osVersion = osVersion;
    this.runtimeName = runtimeName;
    this.runtimeVersion = runtimeVersion;
    this.runtimeVendor = runtimeVendor;
    this.runtimeArchitecture = runtimeArchitecture;
    this.testBundle = testBundle;
    this.custom = custom;
  }

  public String getOsPlatform() {
    return osPlatform;
  }

  public String getOsArchitecture() {
    return osArchitecture;
  }

  public String getOsVersion() {
    return osVersion;
  }

  public String getRuntimeName() {
    return runtimeName;
  }

  public String getRuntimeVersion() {
    return runtimeVersion;
  }

  public String getRuntimeVendor() {
    return runtimeVendor;
  }

  public String getRuntimeArchitecture() {
    return runtimeArchitecture;
  }

  public String getTestBundle() {
    return testBundle;
  }

  public Map<String, String> getCustom() {
    return custom;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Configurations that = (Configurations) o;
    return Objects.equals(osPlatform, that.osPlatform)
        && Objects.equals(osArchitecture, that.osArchitecture)
        && Objects.equals(osVersion, that.osVersion)
        && Objects.equals(runtimeName, that.runtimeName)
        && Objects.equals(runtimeVersion, that.runtimeVersion)
        && Objects.equals(runtimeVendor, that.runtimeVendor)
        && Objects.equals(runtimeArchitecture, that.runtimeArchitecture)
        && Objects.equals(testBundle, that.testBundle)
        && Objects.equals(custom, that.custom);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(
        osPlatform,
        osArchitecture,
        osVersion,
        runtimeName,
        runtimeVersion,
        runtimeVendor,
        runtimeArchitecture,
        testBundle,
        custom);
  }

  @Override
  public String toString() {
    return "Configurations{"
        + "osPlatform='"
        + osPlatform
        + '\''
        + ", osArchitecture='"
        + osArchitecture
        + '\''
        + ", osVersion='"
        + osVersion
        + '\''
        + ", runtimeName='"
        + runtimeName
        + '\''
        + ", runtimeVersion='"
        + runtimeVersion
        + '\''
        + ", runtimeVendor='"
        + runtimeVendor
        + '\''
        + ", runtimeArchitecture='"
        + runtimeArchitecture
        + '\''
        + ", testBundle='"
        + testBundle
        + '\''
        + ", custom="
        + custom
        + '}';
  }
}
