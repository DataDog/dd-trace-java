package com.datadog.appsec.config;

import com.datadog.appsec.AppSecModule;
import java.io.Closeable;
import java.util.Optional;

public interface AppSecConfigService extends Closeable {
  void init();

  Optional<AppSecConfig> addSubConfigListener(String key, SubconfigListener listener);

  interface SubconfigListener {
    void onNewSubconfig(AppSecConfig newConfig) throws AppSecModule.AppSecModuleActivationException;
  }

  void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor);

  void close();
}
