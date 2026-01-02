package datadog.opentelemetry.shim.metrics;

import javax.annotation.Nullable;

final class OtelInstrumentBuilder {

  private final OtelMeter meter;
  private final String instrumentName;
  private final OtelInstrumentType instrumentType;
  private final boolean longValues;

  @Nullable private String description;
  @Nullable private String unit;

  /**
   * Starts building an instrument of long values with the given name and type.
   *
   * @param meter the owning mete
   * @param instrumentName the name of the instrument
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  static OtelInstrumentBuilder ofLongs(
      OtelMeter meter, String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(meter, instrumentName, instrumentType, true);
  }

  /**
   * Starts building an instrument of long values based on another builder.
   *
   * @param builder the builder to copy details from
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  static OtelInstrumentBuilder ofLongs(
      OtelInstrumentBuilder builder, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(builder.meter, builder.instrumentName, instrumentType, true);
  }

  /**
   * Starts building an instrument of double values with the given name and type.
   *
   * @param meter the owning mete
   * @param instrumentName the name of the instrument
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  static OtelInstrumentBuilder ofDoubles(
      OtelMeter meter, String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(meter, instrumentName, instrumentType, false);
  }

  /**
   * Starts building an instrument of double values based on another builder.
   *
   * @param builder the builder to copy details from
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  static OtelInstrumentBuilder ofDoubles(
      OtelInstrumentBuilder builder, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(builder.meter, builder.instrumentName, instrumentType, false);
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
