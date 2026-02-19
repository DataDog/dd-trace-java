package datadog.opentelemetry.shim.metrics;

import javax.annotation.Nullable;

final class OtelInstrumentBuilder {
  private final String instrumentName;
  private final OtelInstrumentType instrumentType;
  private final boolean longValues;

  @Nullable private String description;
  @Nullable private String unit;

  /**
   * Starts building an instrument of long values with the given name and type.
   *
   * @param instrumentName the name of the instrument
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  static OtelInstrumentBuilder ofLongs(String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(instrumentName, instrumentType, true);
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
    return new OtelInstrumentBuilder(builder.instrumentName, instrumentType, true);
  }

  /**
   * Starts building an instrument of double values with the given name and type.
   *
   * @param instrumentName the name of the instrument
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  static OtelInstrumentBuilder ofDoubles(String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(instrumentName, instrumentType, false);
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
    return new OtelInstrumentBuilder(builder.instrumentName, instrumentType, false);
  }

  private OtelInstrumentBuilder(
      String instrumentName, OtelInstrumentType instrumentType, boolean longValues) {
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

  OtelInstrumentDescriptor descriptor() {
    return new OtelInstrumentDescriptor(
        instrumentName, instrumentType, longValues, description, unit);
  }

  OtelInstrumentDescriptor observableDescriptor() {
    return new OtelInstrumentDescriptor(
        instrumentName, observableType(instrumentType), longValues, description, unit);
  }

  /**
   * Maps the given {@link OtelInstrumentType} to its observable equivalent.
   *
   * @throws IllegalArgumentException if the type has no observable equivalent
   */
  private OtelInstrumentType observableType(OtelInstrumentType instrumentType) {
    switch (instrumentType) {
      case COUNTER:
        return OtelInstrumentType.OBSERVABLE_COUNTER;
      case UP_DOWN_COUNTER:
        return OtelInstrumentType.OBSERVABLE_UP_DOWN_COUNTER;
      case GAUGE:
        return OtelInstrumentType.OBSERVABLE_GAUGE;
      default:
        throw new IllegalArgumentException(instrumentType + " has no observable equivalent");
    }
  }
}
