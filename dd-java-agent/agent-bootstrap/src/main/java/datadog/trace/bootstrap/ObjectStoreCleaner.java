package datadog.trace.bootstrap;

import datadog.instrument.fieldinject.GlobalObjectStore;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;

public final class ObjectStoreCleaner {
  private static final long OBJECTSTORE_CLEAN_FREQUENCY_SECONDS = 1;

  /** Schedules periodic removal of stale entries from the global ObjectStore. */
  public static void schedule() {
    if (!InstrumenterConfig.get().isRuntimeContextMapPerStore()) {
      AgentTaskScheduler.get()
          .scheduleAtFixedRate(
              GlobalObjectStore::removeStaleEntries,
              OBJECTSTORE_CLEAN_FREQUENCY_SECONDS,
              OBJECTSTORE_CLEAN_FREQUENCY_SECONDS,
              TimeUnit.SECONDS);
    }
  }

  private ObjectStoreCleaner() {}
}
