package com.datadog.iast.taint;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import javax.annotation.Nonnull;

public class TaintedObjects {

  private final TaintedMap map;

  public TaintedObjects() {
    this(new DefaultTaintedMap());
  }

  public TaintedObjects(final @Nonnull TaintedMap map) {
    this.map = map;
  }

  public void taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
    map.put(new TaintedObject(obj, Ranges.forString(obj, source), map.getReferenceQueue()));
  }

  public void taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
    map.put(new TaintedObject(obj, ranges, map.getReferenceQueue()));
  }

  public TaintedObject get(final @Nonnull Object obj) {
    return map.get(obj);
  }
}
