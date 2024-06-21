package datadog.remoteconfig.state;

import datadog.remoteconfig.Product;
import javax.annotation.Nullable;

public interface ConfigKey {
  Product getProduct();

  String getProductName();

  String getOrg();

  @Nullable
  Integer getVersion();

  String getConfigId();
}
