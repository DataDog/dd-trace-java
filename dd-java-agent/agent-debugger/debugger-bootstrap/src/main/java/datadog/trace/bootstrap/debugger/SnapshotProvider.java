package datadog.trace.bootstrap.debugger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SnapshotProvider {
  private static final Logger LOG = LoggerFactory.getLogger(SnapshotProvider.class);

  public static Snapshot newSnapshot(String uuid, Class<?> callingClass) {
    Snapshot.ProbeDetails probeDetails = DebuggerContext.resolveProbe(uuid, callingClass);
    if (probeDetails == null) {
      LOG.info("Cannot resolve the probe: {}", uuid);
      probeDetails = Snapshot.ProbeDetails.UNKNOWN;
    }
    if (!ProbeRateLimiter.tryProbe(probeDetails.getId())) {
      DebuggerContext.skipSnapshot(probeDetails.getId(), DebuggerContext.SkipCause.RATE);
      return null;
    }
    return new Snapshot(Thread.currentThread(), probeDetails);
  }
}
