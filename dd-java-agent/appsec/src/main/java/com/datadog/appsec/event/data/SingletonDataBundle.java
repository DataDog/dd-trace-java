package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

public class SingletonDataBundle<T> implements DataBundle {
  private final Address<T> address;
  private final T data;

  public SingletonDataBundle(Address<T> address, T data) {
    this.address = address;
    this.data = data;
  }

  @Override
  public boolean hasAddress(Address<?> addr) {
    return addr.equals(this.address);
  }

  @Override
  public Collection<Address<?>> getAllAddresses() {
    return Collections.singletonList(address);
  }

  @Override
  public int size() {
    return 1;
  }

  @Override
  public <T> T get(Address<T> addr) {
    if (addr.equals(this.address)) {
      return (T) data;
    }
    return null;
  }

  @Override
  public Iterator<Map.Entry<Address<?>, Object>> iterator() {
    return new SingletonIterator();
  }

  public class SingletonIterator implements Iterator<Map.Entry<Address<?>, Object>> {
    private boolean hasNext = true;

    @Override
    public boolean hasNext() {
      return hasNext;
    }

    @Override
    public Map.Entry<Address<?>, Object> next() {
      if (hasNext) {
        hasNext = false;
        return new SingletonEntry();
      }
      throw new NoSuchElementException();
    }
  }

  public class SingletonEntry implements Map.Entry<Address<?>, Object> {
    @Override
    public Address<?> getKey() {
      return address;
    }

    @Override
    public Object getValue() {
      return data;
    }

    @Override
    public Object setValue(Object value) {
      throw new UnsupportedOperationException();
    }
  }
}
