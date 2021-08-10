package com.datadog.appsec.config;

import java.io.Closeable;
import java.util.Optional;

public interface AppSecConfigService extends Closeable {
  void init();

  Optional<Object> addSubConfigListener(String key, SubconfigListener listener);

  interface SubconfigListener {
    void onNewSubconfig(Object newConfig);
  }

  void close();
}
