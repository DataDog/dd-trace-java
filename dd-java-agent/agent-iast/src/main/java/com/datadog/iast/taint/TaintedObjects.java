package com.datadog.iast.taint;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaintedObjects {
  private static boolean debug = false;
  private static final Logger log = LoggerFactory.getLogger(TaintedObjects.class);

  private final TaintedMap map;

  public TaintedObjects() {
    this(new DefaultTaintedMap());
  }

  public static void setDebug(Boolean newDebugState) {
    log.debug("TaintedObjects debug mode is " + newDebugState);
    debug = newDebugState;
  }

  public TaintedObjects(final @Nonnull TaintedMap map) {
    this.map = map;
  }

  public void taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
    map.put(new TaintedObject(obj, Ranges.forString(obj, source), map.getReferenceQueue()));
    if (debug) {
      log.debug("TaintInputString: " + obj);
    }
  }

  public void taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
    map.put(new TaintedObject(obj, ranges, map.getReferenceQueue()));
  }

  public TaintedObject get(final @Nonnull Object obj) {
    return map.get(obj);
  }
}
