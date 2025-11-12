package datadog.opentelemetry.shim.metrics;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class MetricStreamIdentity {
  public static MetricStreamIdentity create(
      String instrumentName,
      String description,
      String unit,
      InstrumentType instrumentType,
      InstrumentValueType instrumentValueType) {
    return new AutoValue_MetricStreamIdentity(
        instrumentName, description, unit, instrumentType, instrumentValueType);
  }

  MetricStreamIdentity() {}

  public abstract String instrumentName();

  public abstract String description();

  public abstract String unit();

  public abstract InstrumentType instrumentType();

  public abstract InstrumentValueType instrumentValueType();
}
