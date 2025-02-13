package datadog.trace.core.datastreams;

import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class DataStreamsContextCarrierAdapter
    implements CarrierSetter<DataStreamsContextCarrier>, CarrierVisitor<DataStreamsContextCarrier> {

  public static final DataStreamsContextCarrierAdapter INSTANCE =
      new DataStreamsContextCarrierAdapter();

  private DataStreamsContextCarrierAdapter() {}

  @Override
  public void set(DataStreamsContextCarrier carrier, String key, String value) {
    carrier.set(key, value);
  }

  @Override
  public void forEachKeyValue(
      DataStreamsContextCarrier carrier, BiConsumer<String, String> visitor) {
    for (Map.Entry<String, ?> entry : carrier.entries()) {
      if (null != entry.getValue()) {
        visitor.accept(entry.getKey(), entry.getValue().toString());
      }
    }
  }
}
