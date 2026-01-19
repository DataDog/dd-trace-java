package datadog.opentelemetry.shim.metrics;

/** Ensure all instruments implement the same equivalency. */
abstract class OtelInstrument {
  private final OtelInstrumentDescriptor descriptor;

  OtelInstrument(OtelInstrumentDescriptor descriptor) {
    this.descriptor = descriptor;
  }

  public final OtelInstrumentDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public final boolean equals(Object o) {
    if (!(o instanceof OtelInstrument)) {
      return false;
    }

    OtelInstrument that = (OtelInstrument) o;
    return descriptor.equals(that.descriptor);
  }

  @Override
  public final int hashCode() {
    return descriptor.hashCode();
  }

  @Override
  public final String toString() {
    return getClass().getSimpleName() + "{descriptor=" + descriptor + '}';
  }
}
