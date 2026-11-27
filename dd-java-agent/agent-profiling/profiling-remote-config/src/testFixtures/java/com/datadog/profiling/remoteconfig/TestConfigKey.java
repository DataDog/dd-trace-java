package com.datadog.profiling.remoteconfig;

import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;

/**
 * An {@code APM_PROFILING} config key, equal to another one for the same target file path like the
 * keys remote config parses, unlike a mock.
 */
public final class TestConfigKey implements ConfigKey {
  private final String org;

  /** null for an org without a version, e.g. {@code employee}. */
  private final Integer version;

  private final String configId;

  private TestConfigKey(String org, Integer version, String configId) {
    this.org = org;
    this.version = version;
    this.configId = configId;
  }

  /** The key of the config in the {@code datadog/2} org. */
  public static ConfigKey configKey(String configId) {
    return configKey("datadog", 2, configId);
  }

  /** The key of the config in the org, e.g. {@code datadog} with a version or {@code employee}. */
  public static ConfigKey configKey(String org, Integer version, String configId) {
    return new TestConfigKey(org, version, configId);
  }

  @Override
  public Product getProduct() {
    return Product.APM_PROFILING;
  }

  @Override
  public String getProductName() {
    return Product.APM_PROFILING.name();
  }

  @Override
  public String getOrg() {
    return org;
  }

  @Override
  public Integer getVersion() {
    return version;
  }

  @Override
  public String getConfigId() {
    return configId;
  }

  /** The path of the target file. */
  @Override
  public String toString() {
    return org + (version == null ? "" : "/" + version) + "/APM_PROFILING/" + configId + "/config";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TestConfigKey && toString().equals(o.toString());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }
}
