package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.Json;
import com.squareup.moshi.ToJson;
import datadog.trace.api.civisibility.config.Configurations;

public final class ConfigurationsJson {
  @Json(name = "os.platform")
  private final String osPlatform;

  @Json(name = "os.arch")
  private final String osArch;

  @Json(name = "os.architecture")
  private final String osArchitecture;

  @Json(name = "os.version")
  private final String osVersion;

  @Json(name = "runtime.name")
  private final String runtimeName;

  @Json(name = "runtime.version")
  private final String runtimeVersion;

  @Json(name = "runtime.vendor")
  private final String runtimeVendor;

  @Json(name = "runtime.architecture")
  private final String runtimeArchitecture;

  @Json(name = "test.bundle")
  private final String testBundle;

  public ConfigurationsJson(
      String osPlatform,
      String osArchitecture,
      String osVersion,
      String runtimeName,
      String runtimeVersion,
      String runtimeVendor,
      String runtimeArchitecture,
      String testBundle) {
    this.osPlatform = osPlatform;
    osArch = osArchitecture;
    this.osArchitecture = osArchitecture;
    this.osVersion = osVersion;
    this.runtimeName = runtimeName;
    this.runtimeVersion = runtimeVersion;
    this.runtimeVendor = runtimeVendor;
    this.runtimeArchitecture = runtimeArchitecture;
    this.testBundle = testBundle;
  }

  public static final class ConfigurationsJsonAdapter {
    @FromJson
    public Configurations fromJson(ConfigurationsJson configurationsJson) {
      return new Configurations(
          configurationsJson.osPlatform,
          configurationsJson.osArchitecture,
          configurationsJson.osVersion,
          configurationsJson.runtimeName,
          configurationsJson.runtimeVersion,
          configurationsJson.runtimeVendor,
          configurationsJson.runtimeArchitecture,
          configurationsJson.testBundle);
    }

    @ToJson
    public ConfigurationsJson toJson(Configurations configurations) {
      return new ConfigurationsJson(
          configurations.getOsPlatform(),
          configurations.getOsArchitecture(),
          configurations.getOsVersion(),
          configurations.getRuntimeName(),
          configurations.getRuntimeVersion(),
          configurations.getRuntimeVendor(),
          configurations.getRuntimeArchitecture(),
          configurations.getTestBundle());
    }
  }
}
