package datadog.trace.core.datastreams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class CapturingPayloadWriter implements DatastreamsPayloadWriter {
  volatile boolean accepting = true;
  List<StatsBucket> buckets = new ArrayList<>();

  @Override
  public void writePayload(Collection<StatsBucket> payload, String serviceNameOverride) {
    if (accepting) {
      buckets.addAll(payload);
    }
  }

  public void close() {
    // Stop accepting new buckets so any late submissions by the reporting thread aren't seen
    accepting = false;
  }
}
