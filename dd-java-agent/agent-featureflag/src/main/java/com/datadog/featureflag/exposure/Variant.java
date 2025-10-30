package com.datadog.featureflag.exposure;

import java.util.Objects;

public class Variant {
  public final String key;

  public Variant(final String key) {
    this.key = key;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final Variant variant = (Variant) o;
    return Objects.equals(key, variant.key);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(key);
  }
}
