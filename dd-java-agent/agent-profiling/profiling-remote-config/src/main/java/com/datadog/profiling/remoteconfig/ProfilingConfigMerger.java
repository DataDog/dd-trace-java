package com.datadog.profiling.remoteconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges the {@link RemoteConfiguration}s of a {@link RemoteConfigurationStack} into one, with the
 * value of each feature coming from the configuration with the highest priority that sets it.
 */
public final class ProfilingConfigMerger {
  private static final Logger log = LoggerFactory.getLogger(ProfilingConfigMerger.class);

  private ProfilingConfigMerger() {}

  /**
   * @param stack the accepted configurations
   * @return for each feature, the value of the highest priority configuration that sets it; a
   *     feature that none sets is not set. It is {@link RemoteConfiguration#EMPTY} for an empty
   *     stack.
   */
  public static RemoteConfiguration merge(RemoteConfigurationStack stack) {
    // from the lowest to the highest priority, so that the higher ones overwrite the lower ones
    RemoteConfiguration merged = RemoteConfiguration.EMPTY;
    for (RemoteConfigurationStack.Entry entry : stack.lowestToHighestPriority()) {
      merged = merged.overriddenBy(entry.configuration());
    }
    // the arguments are only turned into text when debug logging is enabled
    log.debug("Merged APM_PROFILING configs, lowest to highest priority: {} -> {}", stack, merged);
    return merged;
  }
}
