package datadog.trace.api.debugger;

import datadog.trace.api.Config;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DebuggerConfigBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerConfigBridge.class);

  private static final int MAX_DEFERRED_UPDATES = 10;
  private static final BlockingQueue<DebuggerConfigUpdate> DEFERRED_UPDATES =
      new ArrayBlockingQueue<>(MAX_DEFERRED_UPDATES);

  private static final AtomicReference<DebuggerConfigUpdater> UPDATER = new AtomicReference<>();

  public static void updateConfig(DebuggerConfigUpdate update) {
    if (!update.hasUpdates()) {
      LOGGER.debug("No config update detected, skipping");
      return;
    }
    if (UPDATER.get() != null) {
      LOGGER.debug("DebuggerConfigUpdater available, performing update");
      UPDATER.get().updateConfig(update);
    } else {
      LOGGER.debug("DebuggerConfigUpdater not available, deferring update");
      if (!DEFERRED_UPDATES.offer(update)) {
        LOGGER.debug("Queue is full, update not deferred");
      }
    }
  }

  public static void setUpdater(@Nonnull DebuggerConfigUpdater updater) {
    DebuggerConfigUpdater oldUpdater = UPDATER.getAndSet(updater);
    if (oldUpdater == null) {
      LOGGER.info("DebuggerConfigUpdater set for first time, processing deferred updates");
      processDeferredUpdates(updater);
    }
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

  private static void processDeferredUpdates(DebuggerConfigUpdater updater) {
    DebuggerConfigUpdate deferredUpdate;
    while ((deferredUpdate = DEFERRED_UPDATES.poll()) != null) {
      updater.updateConfig(deferredUpdate);
      LOGGER.debug("Processed deferred update {}", deferredUpdate);
    }
  }
}
