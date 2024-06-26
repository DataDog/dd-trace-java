package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.Json;
import com.squareup.moshi.ToJson;
import datadog.trace.api.civisibility.config.Configurations;
import java.util.Map;

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

  @Json(name = "custom")
  private final Map<String, String> custom;

  public ConfigurationsJson(
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
    osArch = osArchitecture;
    this.osArchitecture = osArchitecture;
    this.osVersion = osVersion;
    this.runtimeName = runtimeName;
    this.runtimeVersion = runtimeVersion;
    this.runtimeVendor = runtimeVendor;
    this.runtimeArchitecture = runtimeArchitecture;
    this.testBundle = testBundle;
    this.custom = custom;
  }

  public static final class JsonAdapter {
    public static final JsonAdapter INSTANCE = new JsonAdapter();

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
          configurationsJson.testBundle,
          configurationsJson.custom);
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
          configurations.getTestBundle(),
          configurations.getCustom());
    }
  }
}
