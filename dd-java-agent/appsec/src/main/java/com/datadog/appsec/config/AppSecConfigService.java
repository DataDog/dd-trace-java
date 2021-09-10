package com.datadog.appsec.config;

import com.datadog.appsec.AppSecModule;
import java.io.Closeable;
import java.util.Optional;

public interface AppSecConfigService extends Closeable {
  void init(boolean initFleetService);

  Optional<Object> addSubConfigListener(String key, SubconfigListener listener);

  interface SubconfigListener {
    void onNewSubconfig(Object newConfig) throws AppSecModule.AppSecModuleActivationException;
  }

  void close();
}
