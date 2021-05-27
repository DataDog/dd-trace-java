package com.datadog.appsec;

import java.util.Set;

public interface DataBundle {

  @SuppressWarnings("rawtypes")
  Set<Address> getAllAddresses();

  <T> T get(Address<T> address);

  default boolean hasAddress(Address<?> address) {
    return get(address) != null;
  }
}
