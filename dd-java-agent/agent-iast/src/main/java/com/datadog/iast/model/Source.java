package com.datadog.iast.model;

import datadog.trace.util.HashingUtils;
import com.datadog.iast.model.json.SourceTypeString;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.StringJoiner;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

public final class Source implements Taintable.Source {

  private static final Logger LOGGER = LoggerFactory.getLogger(Source.class);

  /** Placeholder for non char sequence objects */
  public static final Object PROPAGATION_PLACEHOLDER = new Object();

  /** value to send in the rare case that the name/value have been garbage collected */
  public static final String GARBAGE_COLLECTED_REF =
      "[unknown: original value was garbage collected]";

  private final @SourceTypeString byte origin;
  @Nullable private final Object name;
  @Nullable private final Object value;
  private boolean gcReported;

  public Source(final byte origin, @Nullable final Object name, @Nullable final Object value) {
    this.origin = origin;
    this.name = name;
    this.value = value;
  }

  @Override
  public byte getOrigin() {
    return origin;
  }

  @Override
  @Nullable
  public String getName() {
    return asString(name);
  }

  @Override
  @Nullable
  public String getValue() {
    return asString(value);
  }

  /** This will expose the internal reference so be careful */
  @Nullable
  public Object getRawValue() {
    if (value == null) {
      return null;
    }
    if (value instanceof Reference<?>) {
      return ((Reference<?>) value).get();
    }
    return value;
  }

  @SuppressFBWarnings(
      value = "DM_STRING_CTOR",
      justification = "New string instance requires constructor")
  @SuppressWarnings("StringOperationCanBeSimplified")
  @Nullable
  private String asString(@Nullable final Object target) {
    Object value = target;
    if (value == PROPAGATION_PLACEHOLDER) {
      value = null;
    } else if (value instanceof Reference) {
      value = ((Reference<?>) value).get();
      if (value != null) {
        // avoid exposing internal weak reference to the outer world
        value = new String(value.toString());
      } else {
        value = GARBAGE_COLLECTED_REF;
        if (!gcReported) {
          gcReported = true;
          LOGGER.debug(
              SEND_TELEMETRY,
              "Source value lost due to GC, origin={}",
              SourceTypes.toString(origin));
        }
      }
    }
    return value instanceof String ? (String) value : null;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", Source.class.getSimpleName() + "[", "]")
        .add("origin=" + SourceTypes.toString(origin))
        .add("name='" + getName() + "'")
        .add("value='" + getValue() + "'")
        .toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Source source = (Source) o;
    return origin == source.origin
        && Objects.equals(getName(), source.getName())
        && Objects.equals(getValue(), source.getValue());
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(origin, getName(), getValue());
  }

  public Source attachValue(final Object newValue) {
    if (value != PROPAGATION_PLACEHOLDER) {
      return this;
    }
    return new Source(origin, name, newValue);
  }

  public boolean isReference() {
    return name instanceof Reference || value instanceof Reference;
  }
}
