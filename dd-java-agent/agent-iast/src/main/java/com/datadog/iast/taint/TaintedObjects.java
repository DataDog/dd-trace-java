package com.datadog.iast.taint;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface TaintedObjects {

  TaintedObject taintInputString(@Nonnull String obj, @Nonnull Source source);

  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  TaintedObject get(@Nonnull Object obj);

  void release();

  static TaintedObjects build() {
    final TaintedObjectsImpl taintedObjects = new TaintedObjectsImpl();
    return IastSystem.DEBUG ? new TaintedObjectsDebugAdapter(taintedObjects) : taintedObjects;
  }

  class TaintedObjectsImpl implements TaintedObjects {

    private final TaintedMap map;

    public TaintedObjectsImpl() {
      this(new DefaultTaintedMap());
    }

    public TaintedObjectsImpl(final @Nonnull TaintedMap map) {
      this.map = map;
    }

    public TaintedObject taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
      final TaintedObject tainted =
          new TaintedObject(obj, Ranges.forString(obj, source), map.getReferenceQueue());
      map.put(tainted);
      return tainted;
    }

    public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
      final TaintedObject tainted = new TaintedObject(obj, ranges, map.getReferenceQueue());
      map.put(tainted);
      return tainted;
    }

    public TaintedObject get(final @Nonnull Object obj) {
      return map.get(obj);
    }

    public void release() {}
  }

  class TaintedObjectsDebugAdapter implements TaintedObjects {
    static final Logger LOGGER = LoggerFactory.getLogger(TaintedObjects.class);

    private final TaintedObjectsImpl delegated;
    private final UUID id;

    public TaintedObjectsDebugAdapter(final TaintedObjectsImpl delegated) {
      this.delegated = delegated;
      this.id = UUID.randomUUID();
      LOGGER.debug("new: id={}", id);
    }

    public TaintedObject taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
      final TaintedObject tainted = delegated.taintInputString(obj, source);
      logTainted(tainted);
      return tainted;
    }

    public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
      final TaintedObject tainted = delegated.taint(obj, ranges);
      logTainted(tainted);
      return tainted;
    }

    public TaintedObject get(final @Nonnull Object obj) {
      return delegated.get(obj);
    }

    public void release() {
      if (IastSystem.DEBUG && LOGGER.isDebugEnabled()) {
        try {
          final List<TaintedObject> entries = new ArrayList<>();
          for (final TaintedObject to : delegated.map) {
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
}
