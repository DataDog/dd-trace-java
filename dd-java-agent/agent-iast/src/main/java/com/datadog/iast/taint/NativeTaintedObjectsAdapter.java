package com.datadog.iast.taint;

import com.datadog.iast.util.Wrapper;
import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.TaintedObject;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.Iterator;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("NullAway")
public class NativeTaintedObjectsAdapter implements TaintedObjects, Wrapper<TaintedObjects> {

  private final Supplier<TaintedObjects> delegateSupplier;
  private volatile TaintedObjects delegate;

  public NativeTaintedObjectsAdapter(@Nonnull final Supplier<TaintedObjects> delegateSupplier) {
    this.delegateSupplier = delegateSupplier;
  }

  public NativeTaintedObjectsAdapter(@Nonnull final TaintedObjects delegate) {
    this.delegateSupplier = null;
    this.delegate = delegate;
  }

  @Nullable
  @Override
  public TaintedObject taint(@Nonnull final Object obj, @Nonnull final Range[] ranges) {
    if (obj instanceof TaintedObject) {
      final TaintedObject to = (TaintedObject) obj;
      to.setRanges(ranges);
      return to;
    }
    return unwrap().taint(obj, ranges);
  }

  @Nullable
  @Override
  public TaintedObject get(@Nonnull final Object obj) {
    if (obj instanceof TaintedObject) {
      return isTainted(obj) ? (TaintedObject) obj : null;
    }
    return unwrap().get(obj);
  }

  @Override
  public boolean isTainted(@Nonnull final Object obj) {
    if (obj instanceof TaintedObject) {
      final TaintedObject to = (TaintedObject) obj;
      return to.getRanges() != null && to.getRanges().length > 0;
    }
    return unwrap().isTainted(obj);
  }

  @Override
  public void clear() {
    unwrap().clear();
  }

  @Override
  public int count() {
    return unwrap().count();
  }

  @Nonnull
  @Override
  public Iterator<TaintedObject> iterator() {
    return unwrap().iterator();
  }

  @Nonnull
  @Override
  public TaintedObjects unwrap() {
    if (delegate == null) {
      final TaintedObjects to = delegateSupplier.get();
      delegate = to == null ? TaintedObjects.NoOp.INSTANCE : to;
    }
    return delegate;
  }
}
