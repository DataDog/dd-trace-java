package com.datadog.featureflag.exposure;

import java.util.Objects;

public class ExposureEvent {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ExposureEvent that = (ExposureEvent) o;
    return Objects.equals(allocation, that.allocation)
        && Objects.equals(flag, that.flag)
        && Objects.equals(variant, that.variant)
        && Objects.equals(subject, that.subject);
  }

  @Override
  public int hashCode() {
    return Objects.hash(allocation, flag, variant, subject);
  }
}
