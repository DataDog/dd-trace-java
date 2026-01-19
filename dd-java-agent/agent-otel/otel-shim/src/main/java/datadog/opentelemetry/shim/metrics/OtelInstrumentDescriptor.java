package datadog.opentelemetry.shim.metrics;

import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;

/** Uniquely describes an instrument for the Meter that created it. */
final class OtelInstrumentDescriptor {
  private final String instrumentName;
  private final OtelInstrumentType instrumentType;
  private final boolean longValues;
  @Nullable private final String description;
  @Nullable private final String unit;

  OtelInstrumentDescriptor(
      String instrumentName,
      OtelInstrumentType instrumentType,
      boolean longValues,
      @Nullable String description,
      @Nullable String unit) {
    this.instrumentName = instrumentName;
    this.instrumentType = instrumentType;
    this.longValues = longValues;
    this.description = description;
    this.unit = unit;
  }

  public String getName() {
    return instrumentName;
  }

  @Nullable
  public String getDescription() {
    return description;
  }

  @Nullable
  public String getUnit() {
    return unit;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OtelInstrumentDescriptor)) {
      return false;
    }

    OtelInstrumentDescriptor that = (OtelInstrumentDescriptor) o;
    return instrumentName.equalsIgnoreCase(that.instrumentName)
        && instrumentType == that.instrumentType
        && longValues == that.longValues
        && Objects.equals(description, that.description)
        && Objects.equals(unit, that.unit);
  }

  @Override
  public int hashCode() {
    int result = instrumentName.toLowerCase(Locale.ROOT).hashCode();
    result = 31 * result + instrumentType.hashCode();
    result = 31 * result + Boolean.hashCode(longValues);
    result = 31 * result + Objects.hashCode(description);
    result = 31 * result + Objects.hashCode(unit);
    return result;
  }

  @Override
  public String toString() {
    // use same property names as OTel in toString
    return "OtelInstrumentDescriptor{"
        + "name='"
        + instrumentName
        + (description != null ? ", description='" + description : "")
        + (unit != null ? "', unit='" + unit : "")
        + "', type="
        + instrumentType
        + ", valueType="
        + (longValues ? "LONG" : "DOUBLE")
        + "}";
  }
}
