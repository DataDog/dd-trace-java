package com.datadog.profiling.remoteconfig;

import java.util.Map;

/**
 * Receives the full set of active {@code APM_PROFILING} configs after each remote-config commit.
 */
@FunctionalInterface
public interface ProfilingConfigHandler {
  ProfilingConfigHandler NOOP = configsById -> {};

  /**
   * @param configsById raw JSON payloads keyed by remote-config config id; empty when all configs
   *     have been removed
   */
  void onConfigsChanged(Map<String, String> configsById);
}
