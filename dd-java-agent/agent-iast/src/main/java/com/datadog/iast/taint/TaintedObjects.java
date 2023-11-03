package com.datadog.iast.taint;

import static java.util.Collections.emptyIterator;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import java.util.Iterator;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnusedReturnValue")
public interface TaintedObjects extends Iterable<TaintedObject> {

  static TaintedObjects build(final int capacity) {
    final TaintedObjectsImpl taintedObjects = new TaintedObjectsImpl(capacity);
    return IastSystem.DEBUG ? new TaintedObjectsDebugAdapter(taintedObjects) : taintedObjects;
  }

  static TaintedObjects build() {
    return build(TaintedMap.DEFAULT_CAPACITY);
  }

  @Nullable
  TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges);

  @Nullable
  TaintedObject get(@Nonnull Object obj);

  void clear();

  class TaintedObjectsImpl implements TaintedObjects {

    private final TaintedMap map;

    public TaintedObjectsImpl(final int capacity) {
      this(new TaintedMap(capacity));
    }

    private TaintedObjectsImpl(final @Nonnull TaintedMap map) {
      this.map = map;
    }

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

    @Nonnull
    @Override
    public Iterator<TaintedObject> iterator() {
      return map.iterator();
    }

    @Override
    public void clear() {
      map.clear();
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
    public void clear() {
      delegated.clear();
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
    @Nonnull
    public Iterator<TaintedObject> iterator() {
      return emptyIterator();
    }

    @Override
    public void clear() {}
  }
}
