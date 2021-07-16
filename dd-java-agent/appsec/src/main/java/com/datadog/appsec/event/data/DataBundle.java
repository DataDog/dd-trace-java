package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.Map;

public interface DataBundle extends Iterable<Map.Entry<Address<?>, Object>> {
  boolean hasAddress(Address<?> addr);

  Collection<Address<?>> getAllAddresses();

  int size();

  // nonnull iif hasAddress(addr) is true
  <T> T get(Address<T> addr);
}
