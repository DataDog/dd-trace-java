package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.DDId;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import jdk.jfr.EventType;

public final class JFRCheckpointer implements Checkpointer {

  private static final boolean RECORD_CPU_TIME =
      ConfigProvider.createDefault()
          .getBoolean(ProfilingConfig.PROFILING_CHECKPOINTS_RECORD_CPU_TIME, false);

  private static final int MASK = RECORD_CPU_TIME ? -1 : ~CPU;

  public JFRCheckpointer() {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads
    // JFR classes - which may not be present on some JVMs
    EventType.getEventType(CheckpointEvent.class);
    EventType.getEventType(EndpointEvent.class);
  }

  @Override
  public void checkpoint(final DDId traceId, final DDId spanId, final int flags) {
    new CheckpointEvent(traceId.toLong(), spanId.toLong(), flags & MASK).commit();
  }

  @Override
  public void onRootSpanPublished(final String endpoint, final DDId traceId) {
    new EndpointEvent(endpoint, traceId.toLong()).commit();
  }
}
