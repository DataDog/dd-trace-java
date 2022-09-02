package com.datadog.iast.taint;

import java.lang.ref.ReferenceQueue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Interface for maps optimized for taint tracking workflows.
 *
 * <p>This is intentionally a smaller API compared to {@link java.util.Map}. It is a hardcoded
 * interface for {@link TaintedObject} entries, which can be used themselves directly as hash table
 * entries and weak references.
 *
 * <p>Any implementation is subject to the following characteristics:
 * <li>Keys MUST be compared with identity.
 * <li>Entries SHOULD be removed when key objects are garbage-collected.
 * <li>All operations MUST NOT throw, even with concurrent access and modification.
 * <li>Put operations MAY be lost under concurrent modification.
 */
public interface TaintedMap extends Iterable<TaintedObject> {

  void put(@Nonnull TaintedObject to);

  @Nullable
  TaintedObject get(@Nonnull Object key);

  void clear();

  ReferenceQueue<Object> getReferenceQueue();
}
