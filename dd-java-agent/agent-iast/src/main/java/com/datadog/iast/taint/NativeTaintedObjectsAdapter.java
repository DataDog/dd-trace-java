package com.datadog.iast.taint;

import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.TaintedObject;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.Collections;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("NullAway")
public class NativeTaintedObjectsAdapter implements TaintedObjects {

  public static final NativeTaintedObjectsAdapter INSTANCE = new NativeTaintedObjectsAdapter();

  @Nullable
  @Override
  public TaintedObject taint(@Nonnull final Object obj, @Nonnull final Range[] ranges) {
    if (obj instanceof TaintedObject) {
      final TaintedObject to = (TaintedObject) obj;
      to.setRanges(ranges);
      return to;
    }
    return null; // only tainted objects
  }

  @Nullable
  @Override
  public TaintedObject get(@Nonnull final Object obj) {
    if (obj instanceof TaintedObject) {
      return isTainted(obj) ? (TaintedObject) obj : null;
    }
    return null;
  }

  @Override
  public boolean isTainted(@Nonnull final Object obj) {
    if (obj instanceof TaintedObject) {
      final TaintedObject to = (TaintedObject) obj;
      return to.getRanges() != null && to.getRanges().length > 0;
    }
    return false;
  }

  @Override
  public void clear() {
    // nothing to do here
  }

  @Override
  public int count() {
    return 0;
  }

  @Nonnull
  @Override
  public Iterator<TaintedObject> iterator() {
    return Collections.emptyIterator();
  }
}
