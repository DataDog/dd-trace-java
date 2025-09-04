package datadog.trace.api.debugger;

import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DebuggerConfigBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerConfigBridge.class);

  private static final AtomicReference<DebuggerConfigUpdate> DEFERRED_UPDATE =
      new AtomicReference<>();

  private static final AtomicReference<DebuggerConfigUpdater> UPDATER = new AtomicReference<>();

  public static void updateConfig(DebuggerConfigUpdate update) {
    if (!update.hasUpdates()) {
      LOGGER.debug("No config update detected, skipping");
      return;
    }
    DebuggerConfigUpdater updater = UPDATER.get();
    if (updater != null) {
      LOGGER.debug("DebuggerConfigUpdater available, performing update: {}", update);
      updater.updateConfig(update);
    } else {
      LOGGER.debug("DebuggerConfigUpdater not available, deferring update");
      DEFERRED_UPDATE.updateAndGet(existing -> DebuggerConfigUpdate.coalesce(existing, update));
    }
  }

  public static void setUpdater(@Nonnull DebuggerConfigUpdater updater) {
    DebuggerConfigUpdater oldUpdater = UPDATER.getAndSet(updater);
    if (oldUpdater == null) {
      LOGGER.debug("DebuggerConfigUpdater set for first time, processing deferred updates");
      processDeferredUpdate(updater);
    }
  }

  // for testing purposes
  static void reset() {
    UPDATER.set(null);
    DEFERRED_UPDATE.set(null);
  }

  public static boolean isDynamicInstrumentationEnabled() {
    DebuggerConfigUpdater updater = UPDATER.get();
    if (updater != null) {
      return updater.isDynamicInstrumentationEnabled();
    }
    return Config.get().isDynamicInstrumentationEnabled();
  }

  public static boolean isExceptionReplayEnabled() {
    DebuggerConfigUpdater updater = UPDATER.get();
    if (updater != null) {
      return updater.isExceptionReplayEnabled();
    }
    return Config.get().isDebuggerExceptionEnabled();
  }

  public static boolean isCodeOriginEnabled() {
    DebuggerConfigUpdater updater = UPDATER.get();
    if (updater != null) {
      return updater.isCodeOriginEnabled();
    }
    return Config.get().isDebuggerCodeOriginEnabled();
  }

  public static boolean isDistributedDebuggerEnabled() {
    DebuggerConfigUpdater updater = UPDATER.get();
    if (updater != null) {
      return updater.isDistributedDebuggerEnabled();
    }
    return Config.get().isDistributedDebuggerEnabled();
  }

  private static void processDeferredUpdate(DebuggerConfigUpdater updater) {
    DebuggerConfigUpdate deferredUpdate = DEFERRED_UPDATE.getAndSet(null);
    if (deferredUpdate != null) {
      updater.updateConfig(deferredUpdate);
      LOGGER.debug("Processed deferred update {}", deferredUpdate);
    }
  }
}
