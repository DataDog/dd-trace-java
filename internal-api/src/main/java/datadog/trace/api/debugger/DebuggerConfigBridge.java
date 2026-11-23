package datadog.trace.api.debugger;

import datadog.trace.api.Config;
import datadog.trace.api.internal.VisibleForTesting;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DebuggerConfigBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerConfigBridge.class);

  private static DebuggerConfigUpdate DEFERRED_UPDATE;
  private static volatile DebuggerConfigUpdater UPDATER;
  private static final Object LOCK = new Object();

  public static void updateConfig(DebuggerConfigUpdate update) {
    synchronized (LOCK) {
      if (!update.hasUpdates()) {
        LOGGER.debug("No config update detected, skipping");
        return;
      }
      applyUpdate(update);
    }
  }

  /**
   * Resets the debugger config to the values held by the static {@link Config}, discarding any
   * remote-config overrides. Used when the remote APM_TRACING override is removed entirely, as
   * opposed to {@link #updateConfig} which only applies partial overrides.
   */
  public static void resetToInitialConfig() {
    synchronized (LOCK) {
      LOGGER.debug("Resetting debugger config to initial state");
      Config config = Config.get();
      applyUpdate(
          new DebuggerConfigUpdate(
              config.isDynamicInstrumentationEnabled(),
              config.isDebuggerExceptionEnabled(),
              // if DI is enabled it enables also CodeOrigin, so we check both for initial state
              config.isDebuggerCodeOriginEnabled() || config.isDynamicInstrumentationEnabled(),
              config.isDistributedDebuggerEnabled()));
    }
  }

  private static void applyUpdate(DebuggerConfigUpdate update) {
    if (UPDATER != null) {
      LOGGER.debug("DebuggerConfigUpdater available, performing update: {}", update);
      UPDATER.updateConfig(update);
    } else {
      LOGGER.debug("DebuggerConfigUpdater not available, deferring update");
      DEFERRED_UPDATE = DebuggerConfigUpdate.coalesce(DEFERRED_UPDATE, update);
    }
  }

  public static void setUpdater(@Nonnull DebuggerConfigUpdater updater) {
    synchronized (LOCK) {
      UPDATER = updater;
      if (DEFERRED_UPDATE != null && DEFERRED_UPDATE.hasUpdates()) {
        LOGGER.debug("Processing deferred update {}", DEFERRED_UPDATE);
        updater.updateConfig(DEFERRED_UPDATE);
        DEFERRED_UPDATE = null;
      }
    }
  }

  @VisibleForTesting
  static synchronized void reset() {
    UPDATER = null;
    DEFERRED_UPDATE = null;
  }

  public static boolean isDynamicInstrumentationEnabled() {
    DebuggerConfigUpdater updater = UPDATER;
    if (updater != null) {
      return updater.isDynamicInstrumentationEnabled();
    }
    return Config.get().isDynamicInstrumentationEnabled();
  }

  public static boolean isExceptionReplayEnabled() {
    DebuggerConfigUpdater updater = UPDATER;
    if (updater != null) {
      return updater.isExceptionReplayEnabled();
    }
    return Config.get().isDebuggerExceptionEnabled();
  }

  public static boolean isCodeOriginEnabled() {
    DebuggerConfigUpdater updater = UPDATER;
    if (updater != null) {
      return updater.isCodeOriginEnabled();
    }
    return Config.get().isDebuggerCodeOriginEnabled();
  }

  public static boolean isDistributedDebuggerEnabled() {
    DebuggerConfigUpdater updater = UPDATER;
    if (updater != null) {
      return updater.isDistributedDebuggerEnabled();
    }
    return Config.get().isDistributedDebuggerEnabled();
  }
}
