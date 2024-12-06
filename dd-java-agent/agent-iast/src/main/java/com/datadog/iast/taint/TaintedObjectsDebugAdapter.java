package com.datadog.iast.taint;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.json.TaintedObjectEncoding;
import com.datadog.iast.util.Wrapper;
import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.TaintedObject;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaintedObjectsDebugAdapter implements TaintedObjects, Wrapper<TaintedObjects> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaintedObjectsDebugAdapter.class);

  private final TaintedObjects delegated;
  private final UUID id;

  public TaintedObjectsDebugAdapter(final TaintedObjects delegated) {
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
  public boolean isTainted(@NotNull Object obj) {
    return delegated.isTainted(obj);
  }

  @Override
  public void clear() {
    if (IastSystem.DEBUG && LOGGER.isDebugEnabled()) {
      try {
        final List<TaintedObject> entries = new ArrayList<>();
        for (final TaintedObject to : delegated) {
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

  private void logTainted(@Nullable final TaintedObject tainted) {
    if (LOGGER.isDebugEnabled()) {
      try {
        if (tainted == null) {
          LOGGER.debug("taint {}: ignored", id);
        } else {
          LOGGER.debug("taint {}: tainted={}", id, TaintedObjectEncoding.toJson(tainted));
        }
      } catch (final Throwable e) {
        LOGGER.error("Failed to debug new tainted object", e);
      }
    }
  }

  @Override
  public TaintedObjects unwrap() {
    return delegated;
  }
}
