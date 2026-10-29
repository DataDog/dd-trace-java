package com.datadog.profiling.remoteconfig;

import java.util.Map;

/** Receives the full set of active {@code DEBUG} configs after each remote-config commit. */
@FunctionalInterface
public interface ProfilingConfigHandler {
  ProfilingConfigHandler NOOP = (configKey, configsById) -> {};

  /**
   * @param configsById raw JSON payloads keyed by remote-config config id; empty when all configs
   *     have been removed
   */
  void onConfigsChanged(String configId, Map<String, String> configsById);
}
