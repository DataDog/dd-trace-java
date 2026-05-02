package datadog.trace.api.featureflag.exposure;

public class ExposureEvent {
  // milliseconds since epoch as given by System.currentTimeMillis()
  public final long timestamp;
  public final Allocation allocation;
  public final Flag flag;
  public final Variant variant;
  public final Subject subject;

  public ExposureEvent(
      final long timestamp,
      final Allocation allocation,
      final Flag flag,
      final Variant variant,
      final Subject subject) {
    this.timestamp = timestamp;
    this.allocation = allocation;
    this.flag = flag;
    this.variant = variant;
    this.subject = subject;
  }
}
