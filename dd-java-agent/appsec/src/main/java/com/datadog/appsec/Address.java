package com.datadog.appsec;

import java.util.Objects;

public final class Address<T> implements Comparable<Address<T>> {
  private final String key;

  Address(String key) {
    this.key = key;
  }

  public String getKey() {
    return key;
  }

  @Override
  public int compareTo(Address<T> o) {
    return this.key.compareTo(o.key);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Address<?> address = (Address<?>) o;
    return Objects.equals(key, address.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key);
  }
}
