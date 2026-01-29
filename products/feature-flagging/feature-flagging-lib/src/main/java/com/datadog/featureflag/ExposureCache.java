package com.datadog.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import java.util.Objects;

public interface ExposureCache {

  boolean add(ExposureEvent event);

  Value get(Key key);

  int size();

  final class Key {
    public final String flag;
    public final String subject;

    public Key(final ExposureEvent event) {
      this.flag = event.flag == null ? null : event.flag.key;
      this.subject = event.subject == null ? null : event.subject.id;
    }

    @Override
    public boolean equals(final Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final Key key = (Key) o;
      return Objects.equals(flag, key.flag) && Objects.equals(subject, key.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(flag, subject);
    }
  }

  final class Value {
    public final String variant;
    public final String allocation;

    public Value(final ExposureEvent event) {
      this.variant = event.variant == null ? null : event.variant.key;
      this.allocation = event.allocation == null ? null : event.allocation.key;
    }

    @Override
    public boolean equals(final Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final Value value = (Value) o;
      return Objects.equals(variant, value.variant) && Objects.equals(allocation, value.allocation);
    }

    @Override
    public int hashCode() {
      return Objects.hash(variant, allocation);
    }
  }
}
