package com.datadog.iast.taint;

import static java.util.Collections.emptyIterator;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import com.datadog.iast.util.Wrapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnusedReturnValue")
public interface TaintedObjects extends Iterable<TaintedObject> {

  static TaintedObjects build(@Nonnull final TaintedMap map) {
    final TaintedObjectsImpl taintedObjects = new TaintedObjectsImpl(map);
    return IastSystem.DEBUG ? new TaintedObjectsDebugAdapter(taintedObjects) : taintedObjects;
  }

  @Nullable
  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  @Nullable
  TaintedObject get(@Nonnull Object obj);

  void clear();

  int count();

  class TaintedObjectsImpl implements TaintedObjects {

    private final TaintedMap map;

    private TaintedObjectsImpl(final @Nonnull TaintedMap map) {
      this.map = map;
    }

    @Nonnull
    @Override
    public TaintedObject taint(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
      final TaintedObject tainted = new TaintedObject(obj, ranges);
      map.put(tainted);
      return tainted;
    }

    @Nullable
    @Override
    public TaintedObject get(final @Nonnull Object obj) {
      return map.get(obj);
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

  final class TaintedObjectsDebugAdapter implements TaintedObjects, Wrapper<TaintedObjectsImpl> {
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
    public void clear() {
      if (IastSystem.DEBUG && LOGGER.isDebugEnabled()) {
        try {
          final List<TaintedObject> entries = new ArrayList<>();
          for (final TaintedObject to : delegated.map) {
            entries.add(to);
          }
          LOGGER.debug("clear {}: map={}", id, TaintedObjectEncoding.toJson(entries));
        } catch (final Throwable e) {
          LOGGER.error("Failed to debug tainted objects release", e);
        }
      }
      delegated.clear();
    }

    @Override
    public int count() {
      return delegated.count();
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

    @Override
    public TaintedObjectsImpl unwrap() {
      return delegated;
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
    public void clear() {}

    @Override
    public int count() {
      return 0;
    }

    @Override
    @Nonnull
    public Iterator<TaintedObject> iterator() {
      return emptyIterator();
    }
  }
}
