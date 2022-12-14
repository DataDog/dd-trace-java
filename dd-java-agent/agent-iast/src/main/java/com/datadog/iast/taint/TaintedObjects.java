package com.datadog.iast.taint;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaintedObjects {
  static final Logger LOGGER = LoggerFactory.getLogger(TaintedObjects.class);

  static boolean DEBUG = false;

  private final TaintedMap map;
  private final UUID id;

  public TaintedObjects() {
    this(new DefaultTaintedMap());
  }

  public static void setDebug(final boolean newDebugState) {
    LOGGER.debug("setDebug: newDebugState={}", newDebugState);
    DEBUG = newDebugState;
  }

  public TaintedObjects(final @Nonnull TaintedMap map) {
    this.map = map;
    if (DEBUG) {
      this.id = UUID.randomUUID();
      LOGGER.debug("new: id={}", id);
    } else {
      this.id = null;
    }
  }

  public void taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
    final TaintedObject tainted =
        new TaintedObject(obj, Ranges.forString(obj, source), map.getReferenceQueue());
    map.put(tainted);
    if (DEBUG) {
      logTainted(tainted);
    }
  }

  public void taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
    final TaintedObject tainted = new TaintedObject(obj, ranges, map.getReferenceQueue());
    map.put(tainted);
    if (DEBUG) {
      logTainted(tainted);
    }
  }

  public TaintedObject get(final @Nonnull Object obj) {
    return map.get(obj);
  }

  public void release() {
    if (DEBUG && LOGGER.isDebugEnabled()) {
      try {
        final List<TaintedObject> entries = new ArrayList<>();
        for (final TaintedObject to : map) {
          entries.add(to);
        }
        LOGGER.debug("release {}: map={}", id, TaintedObjectEncoding.toJson(entries));
      } catch (final Throwable e) {
        LOGGER.error("Failed to debug tainted objects release", e);
      }
    }
  }

  private void logTainted(final TaintedObject tainted) {
    if (LOGGER.isDebugEnabled()) {
      try {
        LOGGER.debug("taint {}: tainted={}", id, TaintedObjectEncoding.toJson(tainted));
      } catch (final Throwable e) {
        LOGGER.error("Failed to debug new tainted object", e);
      }
    }
  }
}
