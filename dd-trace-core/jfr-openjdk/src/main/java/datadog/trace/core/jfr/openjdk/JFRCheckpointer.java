package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.DDId;

public final class JFRCheckpointer implements Checkpointer {
  @Override
  public void checkpoint(DDId traceId, DDId spanId, int flags) {
    new CheckpointEvent(traceId.toLong(), spanId.toLong(), flags).finish();
  }
}
