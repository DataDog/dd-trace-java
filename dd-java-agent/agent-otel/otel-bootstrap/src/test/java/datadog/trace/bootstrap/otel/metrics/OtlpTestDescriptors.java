package datadog.trace.bootstrap.otel.metrics;

/** Test-only factory giving tests access to the package-private descriptor constructor. */
public final class OtlpTestDescriptors {
  private OtlpTestDescriptors() {}

  public static OtelInstrumentDescriptor descriptor(
      String name, OtelInstrumentType type, boolean longValues, String description, String unit) {
    return new OtelInstrumentDescriptor(name, type, longValues, description, unit);
  }
}
