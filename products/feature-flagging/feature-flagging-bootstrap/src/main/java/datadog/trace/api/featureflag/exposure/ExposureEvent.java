package datadog.trace.api.featureflag.exposure;

public class ExposureEvent {
  // milliseconds since epoch as given by System.currentTimeMillis()
  public final long timestamp;
  public final Allocation allocation;
  public final Flag flag;
  public final Variant variant;
  public final Subject subject;

  /**
   * Serial id of the UFC split this exposure resolved to, or null when the split carries none. The
   * field name is the intake's wire key: Moshi serializes these events reflectively, so renaming it
   * to serialId would silently change the payload and the intake would drop the whole event.
   */
  public final Integer serial_id;

  /**
   * Convenience constructor; the serial id defaults to absent. Retained so a provider compiled
   * against an earlier release keeps linking against this class, which ships in the agent while the
   * provider that constructs it ships as the separate dd-openfeature artifact.
   */
  public ExposureEvent(
      final long timestamp,
      final Allocation allocation,
      final Flag flag,
      final Variant variant,
      final Subject subject) {
    this(timestamp, allocation, flag, variant, subject, null);
  }

  public ExposureEvent(
      final long timestamp,
      final Allocation allocation,
      final Flag flag,
      final Variant variant,
      final Subject subject,
      final Integer serialId) {
    this.timestamp = timestamp;
    this.allocation = allocation;
    this.flag = flag;
    this.variant = variant;
    this.subject = subject;
    this.serial_id = serialId;
  }
}
