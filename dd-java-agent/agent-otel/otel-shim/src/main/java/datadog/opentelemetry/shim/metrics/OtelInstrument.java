package datadog.opentelemetry.shim.metrics;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;

/** Ensure all instruments implement the same equivalency. */
abstract class OtelInstrument {
  final OtelMetricStorage storage;

  OtelInstrument(OtelMetricStorage storage) {
    this.storage = storage;
  }

  @Override
  public final boolean equals(Object o) {
    if (!(o instanceof OtelInstrument)) {
      return false;
    }

    OtelInstrument that = (OtelInstrument) o;
    return storage.getDescriptor().equals(that.storage.getDescriptor());
  }

  @Override
  public final int hashCode() {
    return storage.getDescriptor().hashCode();
  }

  @Override
  public final String toString() {
    return getClass().getSimpleName() + "{descriptor=" + storage.getDescriptor() + '}';
  }
}
