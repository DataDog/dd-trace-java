package datadog.remoteconfig.state;

import datadog.remoteconfig.Product;

public interface ConfigKey {
  Product getProduct();

  String getProductName();

  String getOrg();

  Integer getVersion();

  String getConfigId();
}
