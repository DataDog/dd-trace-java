package com.datadog.iast.taint;

import com.datadog.iast.IastSystem;
import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.TaintedObject;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnusedReturnValue")
public class TaintedObjectsMap implements TaintedObjects {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaintedObjectsMap.class);

  public static TaintedObjects build(@Nonnull final TaintedMap map) {
    final TaintedObjectsMap taintedObjects = new TaintedObjectsMap(map);
    return IastSystem.DEBUG ? new TaintedObjectsDebugAdapter(taintedObjects) : taintedObjects;
  }

  private final TaintedMap map;

  private TaintedObjectsMap(final @Nonnull TaintedMap map) {
    this.map = map;
  }

  @Nullable
  @Override
  public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
    try {
      final TaintedObjectEntry tainted = new TaintedObjectEntry(obj, ranges);
      map.put(tainted);
      return tainted;
    } catch (Throwable e) {
      LOGGER.debug("Error tainting object, it won't be tainted", e);
      return null;
    }
  }

  @Nullable
  @Override
  public TaintedObject get(final @Nonnull Object obj) {
    return map.get(obj);
  }

  @Override
  public boolean isTainted(@NotNull Object obj) {
    return get(obj) != null;
  }

  @Override
  public void clear() {
    map.clear();
  }

  @Override
  public int count() {
    return map.count();
  }

  @Nonnull
  @Override
  public Iterator<TaintedObject> iterator() {
    return map.iterator();
  }
}
