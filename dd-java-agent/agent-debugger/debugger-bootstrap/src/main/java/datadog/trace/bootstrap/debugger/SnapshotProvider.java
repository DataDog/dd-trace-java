package datadog.trace.bootstrap.debugger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class used by debugger instrumentation to create a Snapshot Usage of this class is
 * directly emitted as ByteCode in instrumented method
 */
public final class SnapshotProvider {
  private static final Logger LOG = LoggerFactory.getLogger(SnapshotProvider.class);

  public static Snapshot newSnapshot(String uuid, Class<?> callingClass) {
    ProbeImplementation probeImplementation = DebuggerContext.resolveProbe(uuid, callingClass);
    if (probeImplementation == null) {
      LOG.info("Cannot resolve the probe: {}", uuid);
      probeImplementation = ProbeImplementation.UNKNOWN;
    }
    // only rate limit if no condition are defined
    if (probeImplementation.hasCondition() && probeImplementation.isCaptureSnapshot()) {
      if (!ProbeRateLimiter.tryProbe(probeImplementation.getId())) {
        DebuggerContext.skipSnapshot(probeImplementation.getId(), DebuggerContext.SkipCause.RATE);
        return null;
      }
    }
    return new Snapshot(Thread.currentThread(), probeImplementation);
  }
}
