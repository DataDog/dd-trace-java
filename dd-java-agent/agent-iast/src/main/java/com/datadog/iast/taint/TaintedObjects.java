package com.datadog.iast.taint;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;
import static java.util.Collections.emptyIterator;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnusedReturnValue")
public interface TaintedObjects extends Iterable<TaintedObject> {

  @Nullable
  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  @Nullable
  TaintedObject get(@Nonnull Object obj);

  void release();

  int count();

  int getEstimatedSize();

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
      this(new TaintedMap());
    }

    private TaintedObjectsImpl(final @Nonnull TaintedMap map) {
      this.map = map;
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

    @Nonnull
    @Override
    public Iterator<TaintedObject> iterator() {
      return map.iterator();
    }
  }

  final class TaintedObjectsDebugAdapter implements TaintedObjects {
    static final Logger LOGGER = LoggerFactory.getLogger(TaintedObjects.class);

    private final TaintedObjectsImpl delegated;
    private final UUID id;

    public TaintedObjectsDebugAdapter(final TaintedObjectsImpl delegated) {
      this.delegated = delegated;
      id = UUID.randomUUID();
      LOGGER.debug("new: id={}", id);
    }

    @Nullable
    @Override
    public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
      final TaintedObject tainted = delegated.taint(obj, ranges);
      logTainted(tainted);
      return tainted;
    }

    @Nullable
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

    @Nonnull
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

  final class NoOp implements TaintedObjects {

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
