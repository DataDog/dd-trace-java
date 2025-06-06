package com.datadog.appsec.config;

import com.datadog.appsec.AppSecModule;
import com.datadog.ddwaf.exception.AbstractWafException;

public interface AppSecModuleConfigurer {
  void addSubConfigListener(String key, SubconfigListener listener);

  interface SubconfigListener {
    void onNewSubconfig(Object newConfig, Reconfiguration reconfiguration)
        throws AppSecModule.AppSecModuleActivationException, AbstractWafException;
  }

  void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor);

  interface Reconfiguration {
    Reconfiguration NOOP = () -> {};

    void reloadSubscriptions();
  }
}
