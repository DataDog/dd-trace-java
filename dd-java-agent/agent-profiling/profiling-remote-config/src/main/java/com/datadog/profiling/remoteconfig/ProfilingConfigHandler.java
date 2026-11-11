package com.datadog.profiling.remoteconfig;

/**
 * Receives the priority stack of the active {@code APM_PROFILING} configs after each remote-config
 * commit.
 */
@FunctionalInterface
public interface ProfilingConfigHandler {
  /**
   * @param stack a snapshot of the stack, that the poller doesn't change afterwards; empty when all
   *     configs have been removed
   */
  void onConfigsChanged(RemoteConfigurationStack stack);
}
