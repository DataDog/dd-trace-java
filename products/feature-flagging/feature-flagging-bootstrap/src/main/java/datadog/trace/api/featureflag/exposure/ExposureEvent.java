package datadog.trace.api.featureflag.exposure;

import java.util.Map;

public class ExposureEvent {
  // milliseconds since epoch as given by System.currentTimeMillis()
  public final long timestamp;
  public final Allocation allocation;
  public final Flag flag;
  public final Variant variant;
  public final Subject subject;

  public final Integer serial_id;

  // Split features routed to the EXPOSURE destination. Null when there are none, so the field is
  // omitted from the exposure payload.
  public final Map<String, Object> features;

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
    this(timestamp, allocation, flag, variant, subject, serialId, null);
  }

  public ExposureEvent(
      final long timestamp,
      final Allocation allocation,
      final Flag flag,
      final Variant variant,
      final Subject subject,
      final Integer serialId,
      final Map<String, Object> features) {
    this.timestamp = timestamp;
    this.allocation = allocation;
    this.flag = flag;
    this.variant = variant;
    this.subject = subject;
    this.serial_id = serialId;
    this.features = features;
  }
}
