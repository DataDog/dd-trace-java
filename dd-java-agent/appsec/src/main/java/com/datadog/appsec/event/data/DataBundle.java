package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface DataBundle extends Iterable<Map.Entry<Address<?>, Object>> {
  boolean hasAddress(Address<?> addr);

  Collection<Address<?>> getAllAddresses();

  int size();

  // nonnull iif hasAddress(addr) is true
  <T> T get(Address<T> addr);

  static DataBundle unionOf(DataBundle db1, DataBundle db2) {
    return new UnionBundle(db1, db2);
  }

  class UnionBundle implements DataBundle {
    private final DataBundle db1;
    private final DataBundle db2;

    private UnionBundle(DataBundle db1, DataBundle db2) {
      this.db1 = db1;
      this.db2 = db2;
    }

    @Override
    public boolean hasAddress(Address<?> addr) {
      return db1.hasAddress(addr) || db2.hasAddress(addr);
    }

    @Override
    public Collection<Address<?>> getAllAddresses() {
      return Stream.concat(db1.getAllAddresses().stream(), db2.getAllAddresses().stream())
          .collect(Collectors.toList());
    }

    @Override
    public int size() {
      return db1.size() + db2.size();
    }

    @Override
    public <T> T get(Address<T> addr) {
      T t = db1.get(addr);
      if (t != null) {
        return t;
      }
      return db2.get(addr);
    }

    @Override
    public Iterator<Map.Entry<Address<?>, Object>> iterator() {
      return new UnionIterator<>(db1.iterator(), db2.iterator());
    }

    private static class UnionIterator<T> implements Iterator<T> {
      private Iterator<? extends T> activeIterator;
      private final Iterator<? extends T> it2;

      private UnionIterator(Iterator<? extends T> it1, Iterator<? extends T> it2) {
        this.activeIterator = it1;
        this.it2 = it2;
      }

      @Override
      public boolean hasNext() {
        if (activeIterator.hasNext()) {
          return true;
        }
        if (activeIterator == it2) {
          return false;
        }
        activeIterator = it2;
        return it2.hasNext();
      }

      @Override
      public T next() {
        return activeIterator.next();
      }
    }
  }
}
