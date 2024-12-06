package datadog.trace.api.iast.taint;

import java.util.Collections;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface TaintedObjects extends Iterable<TaintedObject> {

  @Nullable
  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  @Nullable
  TaintedObject get(@Nonnull Object obj);

  boolean isTainted(@Nonnull Object obj);

  void clear();

  int count();

  class NoOp implements TaintedObjects {

    public static final TaintedObjects INSTANCE = new NoOp();

    @Nullable
    @Override
    public TaintedObject taint(@Nonnull final Object obj, @Nonnull final Range[] ranges) {
      return null;
    }

    @Nullable
    @Override
    public TaintedObject get(@Nonnull final Object obj) {
      return null;
    }

    @Override
    public boolean isTainted(@Nonnull final Object obj) {
      return false;
    }

    @Override
    public void clear() {}

    @Override
    public int count() {
      return 0;
    }

    @Override
    @Nonnull
    public Iterator<TaintedObject> iterator() {
      return Collections.emptyIterator();
    }
  }
}
