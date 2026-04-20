package datadog.trace.bootstrap.otel.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_UP_DOWN_COUNTER;

import javax.annotation.Nullable;

public final class OtelInstrumentBuilder {
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
  public static OtelInstrumentBuilder ofLongs(
      String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(instrumentName, instrumentType, true);
  }

  /**
   * Starts building an instrument of long values based on another builder.
   *
   * @param builder the builder to copy details from
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  public static OtelInstrumentBuilder ofLongs(
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
  public static OtelInstrumentBuilder ofDoubles(
      String instrumentName, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(instrumentName, instrumentType, false);
  }

  /**
   * Starts building an instrument of double values based on another builder.
   *
   * @param builder the builder to copy details from
   * @param instrumentType the type of the instrument
   * @return new instrument builder
   */
  public static OtelInstrumentBuilder ofDoubles(
      OtelInstrumentBuilder builder, OtelInstrumentType instrumentType) {
    return new OtelInstrumentBuilder(builder.instrumentName, instrumentType, false);
  }

  private OtelInstrumentBuilder(
      String instrumentName, OtelInstrumentType instrumentType, boolean longValues) {
    this.instrumentName = instrumentName;
    this.instrumentType = instrumentType;
    this.longValues = longValues;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public OtelInstrumentDescriptor descriptor() {
    return new OtelInstrumentDescriptor(
        instrumentName, instrumentType, longValues, description, unit);
  }

  public OtelInstrumentDescriptor observableDescriptor() {
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
        return OBSERVABLE_COUNTER;
      case UP_DOWN_COUNTER:
        return OBSERVABLE_UP_DOWN_COUNTER;
      case GAUGE:
        return OBSERVABLE_GAUGE;
      default:
        throw new IllegalArgumentException(instrumentType + " has no observable equivalent");
    }
  }
}
