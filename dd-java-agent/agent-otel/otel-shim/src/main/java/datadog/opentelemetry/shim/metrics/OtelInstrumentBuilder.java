package datadog.opentelemetry.shim.metrics;

final class OtelInstrumentBuilder {

  private final OtelMeter meter;
  private final String instrumentName;
  private final OtelInstrumentType instrumentType;
  private final boolean longValues;

  private String description;
  private String unit;

  static OtelInstrumentBuilder ofLongs(
      OtelMeter meter, String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(meter, instrumentName, instrumentType, true);
  }

  static OtelInstrumentBuilder ofLongs(
      OtelInstrumentBuilder instrumentBuilder, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(
        instrumentBuilder.meter, instrumentBuilder.instrumentName, instrumentType, true);
  }

  static OtelInstrumentBuilder ofDoubles(
      OtelMeter meter, String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(meter, instrumentName, instrumentType, false);
  }

  static OtelInstrumentBuilder ofDoubles(
      OtelInstrumentBuilder instrumentBuilder, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(
        instrumentBuilder.meter, instrumentBuilder.instrumentName, instrumentType, false);
  }

  private OtelInstrumentBuilder(
      OtelMeter meter,
      String instrumentName,
      OtelInstrumentType instrumentType,
      boolean longValues) {
    this.meter = meter;
    this.instrumentName = instrumentName;
    this.instrumentType = instrumentType;
    this.longValues = longValues;
  }

  void setDescription(String description) {
    this.description = description;
  }

  void setUnit(String unit) {
    this.unit = unit;
  }
}
