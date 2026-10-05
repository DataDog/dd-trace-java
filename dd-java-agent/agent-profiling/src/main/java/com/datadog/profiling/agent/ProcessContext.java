package com.datadog.profiling.agent;

import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.Config;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.List;
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
        // Publish the thread-context attribute keys together with the process context so the
        // very first published context already carries the attribute_key_map. The order must
        // match what DatadogProfiler passes to the native attributes= argument and the
        // per-thread ContextSetter, so external readers can decode the thread-local record.
        List<String> attributeKeys = DatadogProfiler.getOrderedContextAttributes(configProvider);
        holder
            .getComponent()
            .initializeAllContext(
                cfg.getEnv(),
                cfg.getHostName(),
                cfg.getRuntimeId(),
                cfg.getServiceName(),
                cfg.getRuntimeVersion(),
                cfg.getVersion(),
                attributeKeys.toArray(new String[0]));
      } else {
        log.warn("Failed to register process context for OTel profiler", err);
      }
    }
  }
}
