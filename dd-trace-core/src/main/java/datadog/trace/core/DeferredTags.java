package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.TagDerivations.slotBit;

import java.util.Arrays;
import java.util.function.Function;

/**
 * A span's pending deferred tags: for each, a source object and the extractor that turns it into
 * the tag's value. The value is extracted once, when something first reads the tag or the span's
 * tags are enumerated, and then stored like any other value. Pending values never enter the tag
 * store, so nothing that reads the store needs to know about them.
 *
 * <p>A source must be value-like: not pooled, recycled or mutated after the tag is set, since it is
 * read later and possibly on another thread. The span keeps it reachable until it is extracted.
 *
 * <p>Not thread-safe: the span calls it under its tag lock.
 */
final class DeferredTags {
  private long[] ids = new long[2];
  private Object[] sources = new Object[2];
  private Function<Object, ?>[] extractors = newExtractors(2);
  private int count;

  /** Slot bits of the pending ids: a clear bit is a definite miss. */
  private long mask;

  @SuppressWarnings("unchecked")
  private static Function<Object, ?>[] newExtractors(int size) {
    return (Function<Object, ?>[]) new Function<?, ?>[size];
  }

  boolean isEmpty() {
    return count == 0;
  }

  boolean mayHold(long tagId) {
    return (mask & slotBit(tagId)) != 0L;
  }

  /** Makes a tag pending, replacing any pending value for it. */
  @SuppressWarnings("unchecked")
  <T> void put(long tagId, T source, Function<? super T, ?> extractor) {
    int i = indexOf(tagId);
    if (i < 0) {
      if (count == ids.length) {
        int size = count << 1;
        ids = Arrays.copyOf(ids, size);
        sources = Arrays.copyOf(sources, size);
        extractors = Arrays.copyOf(extractors, size);
      }
      i = count++;
      ids[i] = tagId;
      mask |= slotBit(tagId);
    }
    sources[i] = source;
    extractors[i] = (Function<Object, ?>) extractor;
  }

  /**
   * Drops a pending tag, as an explicit write or removal does.
   *
   * @return whether one was pending
   */
  boolean remove(long tagId) {
    if (!mayHold(tagId)) {
      return false;
    }
    int i = indexOf(tagId);
    if (i < 0) {
      return false;
    }
    removeAt(i);
    return true;
  }

  /**
   * Takes a pending tag out and extracts its value. An extractor that throws leaves the tag absent.
   *
   * @return the value, or null if none was pending or extraction produced none
   */
  Object extract(long tagId) {
    if (!mayHold(tagId)) {
      return null;
    }
    int i = indexOf(tagId);
    if (i < 0) {
      return null;
    }
    Object source = sources[i];
    Function<Object, ?> extractor = extractors[i];
    removeAt(i);
    return apply(extractor, source);
  }

  /** The id of some pending tag, for draining them all; only valid when not {@link #isEmpty}. */
  long anyId() {
    return ids[count - 1];
  }

  private static Object apply(Function<Object, ?> extractor, Object source) {
    try {
      return extractor.apply(source);
    } catch (Throwable t) {
      return null; // the tag stays absent, as if never set
    }
  }

  private int indexOf(long tagId) {
    for (int i = 0; i < count; ++i) {
      if (ids[i] == tagId) {
        return i;
      }
    }
    return -1;
  }

  private void removeAt(int i) {
    int last = --count;
    ids[i] = ids[last];
    sources[i] = sources[last];
    extractors[i] = extractors[last];
    sources[last] = null; // don't keep the source reachable
    extractors[last] = null;
    // The mask isn't narrowed: a stale bit only costs a scan.
  }
}
