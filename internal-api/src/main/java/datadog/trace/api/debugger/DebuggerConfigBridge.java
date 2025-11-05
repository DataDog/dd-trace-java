package datadog.trace.api.debugger;

import datadog.trace.api.Config;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DebuggerConfigBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerConfigBridge.class);

  private static DebuggerConfigUpdate DEFERRED_UPDATE;
  private static volatile DebuggerConfigUpdater UPDATER;

  public static synchronized void updateConfig(DebuggerConfigUpdate update) {
    if (!update.hasUpdates()) {
      LOGGER.debug("No config update detected, skipping");
      return;
    }
    if (UPDATER != null) {
      LOGGER.debug("DebuggerConfigUpdater available, performing update: {}", update);
      UPDATER.updateConfig(update);
    } else {
      LOGGER.debug("DebuggerConfigUpdater not available, deferring update");
      DEFERRED_UPDATE = DebuggerConfigUpdate.coalesce(DEFERRED_UPDATE, update);
    }
  }

  public static synchronized void setUpdater(@Nonnull DebuggerConfigUpdater updater) {
    UPDATER = updater;
    if (DEFERRED_UPDATE != null && DEFERRED_UPDATE.hasUpdates()) {
      LOGGER.debug("Processing deferred update {}", DEFERRED_UPDATE);
      updater.updateConfig(DEFERRED_UPDATE);
      DEFERRED_UPDATE = null;
    }
  }

  // for testing purposes
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
