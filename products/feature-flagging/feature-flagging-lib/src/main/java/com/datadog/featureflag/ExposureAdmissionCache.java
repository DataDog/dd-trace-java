package com.datadog.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * A bounded, thread-safe cache for exposure admission on application evaluation threads.
 *
 * <p>The serializer keeps the authoritative LRU cache. This cache can only avoid work for an exact
 * recent match. An eviction or a concurrent miss creates an extra event, which the serializer
 * removes. It does not remove a changed exposure.
 */
final class ExposureAdmissionCache {

  private static final int LOCK_COUNT = 64;

  private final int capacity;
  private final ConcurrentMap<Key, Value> identities = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<Key> insertionOrder = new ConcurrentLinkedQueue<>();
  private final Object[] locks = new Object[LOCK_COUNT];
  private volatile boolean closed;

  ExposureAdmissionCache(final int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new Object();
    }
  }

  boolean contains(
      final String flag, final String subject, final String variant, final String allocation) {
    final Value current = identities.get(new Key(flag, subject));
    return current != null && current.matches(variant, allocation);
  }

  void add(final ExposureEvent event) {
    if (closed) {
      return;
    }
    final Key key =
        new Key(
            event.flag == null ? null : event.flag.key,
            event.subject == null ? null : event.subject.id);
    final Value value =
        new Value(
            event.variant == null ? null : event.variant.key,
            event.allocation == null ? null : event.allocation.key);
    final Value previous = identities.put(key, value);
    if (previous == null) {
      insertionOrder.offer(key);
      evictExcess();
    }
  }

  Object lockFor(final ExposureEvent event) {
    final String flag = event.flag == null ? null : event.flag.key;
    final String subject = event.subject == null ? null : event.subject.id;
    int hash = flag == null ? 0 : flag.hashCode();
    hash = 31 * hash + (subject == null ? 0 : subject.hashCode());
    return locks[(hash ^ (hash >>> 16)) & (locks.length - 1)];
  }

  void clear() {
    identities.clear();
    insertionOrder.clear();
  }

  void close() {
    closeWithLocksHeld(0);
  }

  boolean isClosed() {
    return closed;
  }

  int size() {
    return identities.size();
  }

  private void evictExcess() {
    while (identities.size() > capacity) {
      final Key oldest = insertionOrder.poll();
      if (oldest == null) {
        return;
      }
      identities.remove(oldest);
    }
  }

  private void closeWithLocksHeld(final int index) {
    if (index == locks.length) {
      closed = true;
      clear();
      return;
    }
    synchronized (locks[index]) {
      closeWithLocksHeld(index + 1);
    }
  }

  static final class Key {
    private final String flag;
    private final String subject;

    Key(final String flag, final String subject) {
      this.flag = flag;
      this.subject = subject;
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key)) {
        return false;
      }
      final Key key = (Key) other;
      return Objects.equals(flag, key.flag) && Objects.equals(subject, key.subject);
    }

    @Override
    public int hashCode() {
      int result = flag == null ? 0 : flag.hashCode();
      result = 31 * result + (subject == null ? 0 : subject.hashCode());
      return result;
    }
  }

  private static final class Value {
    private final String variant;
    private final String allocation;

    private Value(final String variant, final String allocation) {
      this.variant = variant;
      this.allocation = allocation;
    }

    private boolean matches(final String otherVariant, final String otherAllocation) {
      return Objects.equals(variant, otherVariant) && Objects.equals(allocation, otherAllocation);
    }
  }
}
