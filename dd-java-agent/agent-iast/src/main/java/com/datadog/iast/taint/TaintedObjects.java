package com.datadog.iast.taint;

import static com.datadog.iast.model.Range.NOT_MARKED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;
import static java.util.Collections.emptyIterator;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface TaintedObjects extends Iterable<TaintedObject> {

  TaintedObject taintInputString(@Nonnull String obj, @Nonnull Source source, int mark);

  TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source, int mark);

  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  TaintedObject get(@Nonnull Object obj);

  void release();

  int count();

  int getEstimatedSize();

  boolean isFlat();

  default TaintedObject taintInputString(@Nonnull String obj, @Nonnull Source source) {
    return taintInputString(obj, source, NOT_MARKED);
  }

  default TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source) {
    return taintInputObject(obj, source, NOT_MARKED);
  }

  static TaintedObjects acquire() {
    TaintedObjectsImpl taintedObjects = TaintedObjectsImpl.pool.poll();
    if (taintedObjects == null) {
      taintedObjects = new TaintedObjectsImpl();
    }
    return IastSystem.DEBUG ? new TaintedObjectsDebugAdapter(taintedObjects) : taintedObjects;
  }

  static TaintedObjects activeTaintedObjects(boolean lazy) {
    if (lazy) {
      return new LazyTaintedObjects();
    } else {
      final IastRequestContext ctx = IastRequestContext.get();
      if (ctx != null) {
        return ctx.getTaintedObjects();
      }
      return null;
    }
  }

  static TaintedObjects activeTaintedObjects() {
    return activeTaintedObjects(false);
  }

  class TaintedObjectsImpl implements TaintedObjects {

    private static final ArrayBlockingQueue<TaintedObjectsImpl> pool =
        new ArrayBlockingQueue<>(
            Math.max(
                Config.get().getIastMaxConcurrentRequests(), DEFAULT_IAST_MAX_CONCURRENT_REQUESTS));

    private final TaintedMap map;

    public TaintedObjectsImpl() {
      this(new TaintedMap());
    }

    private TaintedObjectsImpl(final @Nonnull TaintedMap map) {
      this.map = map;
    }

    @Override
    public TaintedObject taintInputString(
        final @Nonnull String obj, final @Nonnull Source source, final int mark) {
      final TaintedObject tainted =
          new TaintedObject(obj, Ranges.forString(obj, source, mark), map.getReferenceQueue());
      map.put(tainted);
      return tainted;
    }

    @Override
    public TaintedObject taintInputObject(
        @Nonnull Object obj, @Nonnull Source source, final int mark) {
      final TaintedObject tainted =
          new TaintedObject(obj, Ranges.forObject(source, mark), map.getReferenceQueue());
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

    @Override
    public void release() {
      map.clear();
      pool.offer(this);
    }

    @Override
    public int count() {
      return map.count();
    }

    @Override
    public int getEstimatedSize() {
      return map.getEstimatedSize();
    }

    @Override
    public boolean isFlat() {
      return map.isFlat();
    }

    @Override
    public Iterator<TaintedObject> iterator() {
      return map.iterator();
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
    public TaintedObject taintInputString(
        final @Nonnull String obj, final @Nonnull Source source, final int mark) {
      final TaintedObject tainted = delegated.taintInputString(obj, source, mark);
      logTainted(tainted);
      return tainted;
    }

    @Override
    public TaintedObject taintInputObject(
        @Nonnull Object obj, @Nonnull Source source, final int mark) {
      final TaintedObject tainted = delegated.taintInputObject(obj, source, mark);
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
    public int count() {
      return delegated.count();
    }

    @Override
    public int getEstimatedSize() {
      return delegated.getEstimatedSize();
    }

    @Override
    public boolean isFlat() {
      return delegated.isFlat();
    }

    @Override
    public Iterator<TaintedObject> iterator() {
      return delegated.iterator();
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

  class LazyTaintedObjects implements TaintedObjects {
    private boolean fetched = false;
    private TaintedObjects taintedObjects;

    @Override
    public TaintedObject taintInputString(
        @Nonnull final String obj, @Nonnull final Source source, final int mark) {
      final TaintedObjects to = getTaintedObjects();
      return to == null ? null : to.taintInputString(obj, source, mark);
    }

    @Override
    public TaintedObject taintInputObject(
        @Nonnull final Object obj, @Nonnull final Source source, final int mark) {
      final TaintedObjects to = getTaintedObjects();
      return to == null ? null : to.taintInputObject(obj, source, mark);
    }

    @Override
    public TaintedObject taint(@Nonnull final Object obj, @Nonnull final Range[] ranges) {
      final TaintedObjects to = getTaintedObjects();
      return to == null ? null : to.taint(obj, ranges);
    }

    @Override
    public TaintedObject get(@Nonnull final Object obj) {
      final TaintedObjects to = getTaintedObjects();
      return to == null ? null : to.get(obj);
    }

    @Override
    public void release() {
      final TaintedObjects to = getTaintedObjects();
      if (to != null) {
        to.release();
      }
    }

    @Override
    public Iterator<TaintedObject> iterator() {
      final TaintedObjects to = getTaintedObjects();
      return to != null ? to.iterator() : emptyIterator();
    }

    @Override
    public int getEstimatedSize() {
      final TaintedObjects to = getTaintedObjects();
      return to != null ? to.getEstimatedSize() : 0;
    }

    @Override
    public boolean isFlat() {
      final TaintedObjects to = getTaintedObjects();
      return to != null && to.isFlat();
    }

    @Override
    public int count() {
      final TaintedObjects to = getTaintedObjects();
      return to != null ? to.count() : 0;
    }

    private TaintedObjects getTaintedObjects() {
      if (!fetched) {
        fetched = true;
        taintedObjects = activeTaintedObjects();
      }
      return taintedObjects;
    }
  }

  enum NoOp implements TaintedObjects {
    INSTANCE;

    @Override
    public TaintedObject taintInputString(
        @Nonnull final String obj, @Nonnull final Source source, final int mark) {
      return null;
    }

    @Override
    public TaintedObject taintInputObject(
        @Nonnull final Object obj, @Nonnull final Source source, final int mark) {
      return null;
    }

    @Override
    public TaintedObject taint(@Nonnull final Object obj, @Nonnull final Range[] ranges) {
      return null;
    }

    @Override
    public TaintedObject get(@Nonnull final Object obj) {
      return null;
    }

    @Override
    public void release() {}

    @Override
    public boolean isFlat() {
      return false;
    }

    @Override
    public int count() {
      return 0;
    }

    @Override
    public int getEstimatedSize() {
      return 0;
    }

    @Override
    @Nonnull
    public Iterator<TaintedObject> iterator() {
      return emptyIterator();
    }
  }
}
