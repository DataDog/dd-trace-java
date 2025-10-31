package com.datadog.featureflag.exposure;

import java.util.Objects;

public class Allocation {
  public final String key;

  public Allocation(final String key) {
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
    Allocation that = (Allocation) o;
    return Objects.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(key);
  }
}
