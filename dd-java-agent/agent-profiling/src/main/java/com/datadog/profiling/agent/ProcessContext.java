package com.datadog.profiling.agent;

import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.Config;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProcessContext {
  private static final Logger log = LoggerFactory.getLogger(ProcessContext.class.getName());

  public static void register(ConfigProvider configProvider) {
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED,
        ProfilingConfig.PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT)) {
      log.info("Registering process context for OTel profiler");
      DdprofLibraryLoader.OTelContextHolder holder = DdprofLibraryLoader.otelContext();
      Throwable err = holder.getReasonNotLoaded();
      if (err == null) {
        Config cfg = Config.get();
        holder
            .getComponent()
            .setProcessContext(
                cfg.getEnv(),
                cfg.getHostName(),
                cfg.getRuntimeId(),
                cfg.getServiceName(),
                cfg.getRuntimeVersion(),
                cfg.getVersion());
      } else {
        log.warn("Failed to register process context for OTel profiler", err);
      }
    }
  }
}
