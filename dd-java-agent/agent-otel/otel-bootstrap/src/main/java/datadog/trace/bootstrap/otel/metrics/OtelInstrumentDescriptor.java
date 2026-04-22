package datadog.trace.bootstrap.otel.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;

/** Uniquely describes an instrument for the Meter that created it. */
public final class OtelInstrumentDescriptor {
  private final UTF8BytesString instrumentName;
  private final OtelInstrumentType instrumentType;
  private final boolean longValues;
  @Nullable private final UTF8BytesString description;
  @Nullable private final UTF8BytesString unit;
  private int hash;

  public OtelInstrumentDescriptor(
      String instrumentName,
      OtelInstrumentType instrumentType,
      boolean longValues,
      @Nullable String description,
      @Nullable String unit) {
    this.instrumentName = UTF8BytesString.create(instrumentName);
    this.instrumentType = instrumentType;
    this.longValues = longValues;
    this.description = UTF8BytesString.create(description);
    this.unit = UTF8BytesString.create(unit);
  }

  public UTF8BytesString getName() {
    return instrumentName;
  }

  public OtelInstrumentType getType() {
    return instrumentType;
  }

  public boolean hasLongValues() {
    return longValues;
  }

  @Nullable
  public UTF8BytesString getDescription() {
    return description;
  }

  @Nullable
  public UTF8BytesString getUnit() {
    return unit;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OtelInstrumentDescriptor)) {
      return false;
    }

    OtelInstrumentDescriptor that = (OtelInstrumentDescriptor) o;
    return instrumentName.toString().equalsIgnoreCase(that.instrumentName.toString())
        && instrumentType == that.instrumentType
        && longValues == that.longValues
        && Objects.equals(description, that.description)
        && Objects.equals(unit, that.unit);
  }

  @Override
  public int hashCode() {
    int result = hash;
    if (result == 0) {
      result = instrumentName.toString().toLowerCase(Locale.ROOT).hashCode();
      result = 31 * result + instrumentType.hashCode();
      result = 31 * result + Boolean.hashCode(longValues);
      result = 31 * result + Objects.hashCode(description);
      result = 31 * result + Objects.hashCode(unit);
      hash = result;
    }
    return result;
  }

  @Override
  public String toString() {
    // use same property names as OTel in toString
    return "OtelInstrumentDescriptor{"
        + "name='"
        + instrumentName
        + (description != null ? "', description='" + description : "")
        + (unit != null ? "', unit='" + unit : "")
        + "', type="
        + instrumentType
        + ", valueType="
        + (longValues ? "LONG" : "DOUBLE")
        + "}";
  }
}
