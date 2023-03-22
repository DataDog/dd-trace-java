package com.datadog.appsec.config;

import com.datadog.appsec.AppSecModule;
import java.util.Optional;

public interface AppSecModuleConfigurer {
  Optional<Object> addSubConfigListener(String key, SubconfigListener listener);

  interface SubconfigListener {
    void onNewSubconfig(Object newConfig, Reconfiguration reconfiguration)
        throws AppSecModule.AppSecModuleActivationException;
  }

  void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor);

  interface Reconfiguration {
    Reconfiguration NOOP = () -> {};

    void reloadSubscriptions();
  }
}
