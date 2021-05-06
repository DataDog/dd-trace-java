package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.DDId;
import jdk.jfr.EventType;

public final class JFRCheckpointer implements Checkpointer {

  public JFRCheckpointer() {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads
    // JFR classes - which may not be present on some JVMs
    EventType.getEventType(CheckpointEvent.class);
  }

  @Override
  public void checkpoint(DDId traceId, DDId spanId, int flags) {
    new CheckpointEvent(traceId.toLong(), spanId.toLong(), flags).commit();
  }
}
