package com.datadog.appsec.config;

import com.datadog.appsec.AppSecModule;
import java.io.Closeable;
import java.util.Optional;

public interface AppSecConfigService extends Closeable {
  void init();

  // separated from init() to avoid the modules being configured concurrently
  // via 1) remote config and 2) the initial module registration
  void maybeInitPoller();

  Optional<AppSecConfig> addSubConfigListener(String key, SubconfigListener listener);

  interface SubconfigListener {
    void onNewSubconfig(AppSecConfig newConfig) throws AppSecModule.AppSecModuleActivationException;
  }

  void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor);

  void close();
}
