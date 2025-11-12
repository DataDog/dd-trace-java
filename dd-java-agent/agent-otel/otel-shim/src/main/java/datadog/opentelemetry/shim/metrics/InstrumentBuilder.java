package datadog.opentelemetry.shim.metrics;

public class InstrumentBuilder {

  private final String instrumentName;
  private final InstrumentType instrumentType;
  private final InstrumentValueType instrumentValueType;
  private final OtelMeter otelMeter;
  private String description = "";
  private String unit = "";

  public InstrumentBuilder(
      OtelMeter meter,
      String instrumentName,
      InstrumentType instrumentType,
      InstrumentValueType instrumentValueType) {
    this.instrumentName = instrumentName;
    this.instrumentType = instrumentType;
    this.instrumentValueType = instrumentValueType;
    this.otelMeter = meter;
  }

  public InstrumentBuilder setDescription(String description) {
    this.description = description;
    return this;
  }

  public InstrumentBuilder setUnit(String unit) {
    this.unit = unit;
    return this;
  }

  @FunctionalInterface
  interface SynchronousInstrumentConstructor<T extends Instrument> {
    T createInstrument(OtelMeter otelMeter, MetricStreamIdentity metricsStreamIdentity);
  }

  <T extends Instrument> T buildSynchronousInstrument(
      SynchronousInstrumentConstructor<T> synchronousInstrumentConstructor) {
    MetricStreamIdentity metricStreamIdentity = newMetricStreamIdentity();
    // to do: add a storage in createInstrument
    return synchronousInstrumentConstructor.createInstrument(otelMeter, metricStreamIdentity);
  }

  @FunctionalInterface
  interface SwapBuilder<T> {
    T newBuilder(OtelMeter otelMeter, String instrumentName, String description, String unit);
  }

  <T> T swapBuilder(SwapBuilder<T> swapper) {
    return swapper.newBuilder(otelMeter, instrumentName, description, unit);
  }

  private MetricStreamIdentity newMetricStreamIdentity() {
    return MetricStreamIdentity.create(
        instrumentName, description, unit, instrumentType, instrumentValueType);
  }
}
