package datadog.trace.core.datastreams;

import java.util.Collection;

public interface DatastreamsPayloadWriter {
  void writePayload(Collection<StatsBucket> data, String serviceNameOverride);
}
