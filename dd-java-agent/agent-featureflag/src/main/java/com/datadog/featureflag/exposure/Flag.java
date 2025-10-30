package com.datadog.featureflag.exposure;

import java.util.Objects;

public class Flag {
  public final String key;

  public Flag(final String key) {
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
    final Flag flag = (Flag) o;
    return Objects.equals(key, flag.key);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(key);
  }
}
