package com.datadog.iast.taint;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface TaintedObjects {

  TaintedObject taintInputString(@Nonnull String obj, @Nonnull Source source);

  TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source);

  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  TaintedObject get(@Nonnull Object obj);

  void release();

  long getEstimatedSize();

  boolean isFlat();

  static TaintedObjects acquire() {
    TaintedObjectsImpl taintedObjects = TaintedObjectsImpl.pool.poll();
    if (taintedObjects == null) {
      taintedObjects = new TaintedObjectsImpl();
    }
    return IastSystem.DEBUG ? new TaintedObjectsDebugAdapter(taintedObjects) : taintedObjects;
  }

  class TaintedObjectsImpl implements TaintedObjects {

    private static final ArrayBlockingQueue<TaintedObjectsImpl> pool =
        new ArrayBlockingQueue<>(
            Math.max(
                Config.get().getIastMaxConcurrentRequests(), DEFAULT_IAST_MAX_CONCURRENT_REQUESTS));

    private final TaintedMap map;

    public TaintedObjectsImpl() {
      this(new DefaultTaintedMap());
    }

    private TaintedObjectsImpl(final @Nonnull TaintedMap map) {
      this.map = map;
    }

    @Override
    public TaintedObject taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
      final TaintedObject tainted =
          new TaintedObject(obj, Ranges.forString(obj, source), map.getReferenceQueue());
      map.put(tainted);
      return tainted;
    }

    @Override
    public TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source) {
      final TaintedObject tainted =
          new TaintedObject(obj, Ranges.forObject(source), map.getReferenceQueue());
      map.put(tainted);
      return tainted;
    }

    @Override
    public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
      final TaintedObject tainted = new TaintedObject(obj, ranges, map.getReferenceQueue());
      map.put(tainted);
      return tainted;
    }

    @Override
    public TaintedObject get(final @Nonnull Object obj) {
      return map.get(obj);
    }

    public void release() {
      map.clear();
      pool.offer(this);
    }

    @Override
    public long getEstimatedSize() {
      return map.getEstimatedSize();
    }

    @Override
    public boolean isFlat() {
      return map.isFlat();
    }
  }

  class TaintedObjectsDebugAdapter implements TaintedObjects {
    static final Logger LOGGER = LoggerFactory.getLogger(TaintedObjects.class);

    private final TaintedObjectsImpl delegated;
    private final UUID id;

    public TaintedObjectsDebugAdapter(final TaintedObjectsImpl delegated) {
      this.delegated = delegated;
      id = UUID.randomUUID();
      LOGGER.debug("new: id={}", id);
    }

    @Override
    public TaintedObject taintInputString(final @Nonnull String obj, final @Nonnull Source source) {
      final TaintedObject tainted = delegated.taintInputString(obj, source);
      logTainted(tainted);
      return tainted;
    }

    @Override
    public TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source) {
      final TaintedObject tainted = delegated.taintInputObject(obj, source);
      logTainted(tainted);
      return tainted;
    }

    @Override
    public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
      final TaintedObject tainted = delegated.taint(obj, ranges);
      logTainted(tainted);
      return tainted;
    }

    @Override
    public TaintedObject get(final @Nonnull Object obj) {
      return delegated.get(obj);
    }

    @Override
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
      delegated.release();
    }

    @Override
    public long getEstimatedSize() {
      return delegated.getEstimatedSize();
    }

    @Override
    public boolean isFlat() {
      return delegated.isFlat();
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
