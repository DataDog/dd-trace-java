package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier;
import java.util.Map;

public class DataStreamsContextCarrierAdapter
    implements AgentPropagation.Setter<DataStreamsContextCarrier>,
        AgentPropagation.ContextVisitor<DataStreamsContextCarrier> {

  public static final DataStreamsContextCarrierAdapter INSTANCE =
      new DataStreamsContextCarrierAdapter();

  private DataStreamsContextCarrierAdapter() {}

  @Override
  public void set(DataStreamsContextCarrier carrier, String key, String value) {
    carrier.set(key, value);
  }

  @Override
  public void forEachKey(DataStreamsContextCarrier carrier, KeyClassifier classifier) {
    for (Map.Entry<String, ?> entry : carrier.entries()) {
      if (null != entry.getValue()
          && !classifier.accept(entry.getKey(), entry.getValue().toString())) {
        return;
      }
    }
  }
}
