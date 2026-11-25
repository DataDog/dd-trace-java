package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.TagDerivations.slotBit;

import datadog.trace.bootstrap.instrumentation.api.TagDerivations;
import datadog.trace.bootstrap.instrumentation.api.TagReader;
import datadog.trace.bootstrap.instrumentation.api.TagWriter;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One tag owner's derived-tag state: which formulas have run, what they read, and which stored
 * values they produced. The owner's store doubles as the cache: a formula writes its outputs into
 * it, and they are remembered as derived so a later explicit write or an input change can undo
 * them.
 *
 * <p>Invalidation: every read a formula makes ORs the tag's slot bit into {@link #depMask},
 * including reads of absent tags and each formula's own targets. A write to a tag whose bit is set
 * removes every derived value and lets the formulas run again on the next read. Slot bits can be
 * shared, so this may recompute needlessly, but never leaves a stale value. One mask for the whole
 * owner means a formula that reads another derived tag picks up that tag's inputs too.
 *
 * <p>Each owner extends it with its storage and fields, so the state and its link to the owner are
 * one object.
 *
 * <p>Not thread-safe: the owner calls it under its own lock.
 */
abstract class DerivedTagState implements TagReader, TagWriter {
  private static final Logger log = LoggerFactory.getLogger(DerivedTagState.class);

  /** The stored value only, never running a formula. */
  abstract Object storedValue(long tagId);

  /** The stored value, else the value a formula produces. */
  abstract Object visibleValue(long tagId);

  /** Stores a formula's output, without the owner's write hooks. */
  abstract void storeDerived(long tagId, Object value);

  /** Removes a formula's output, without the owner's write hooks. */
  abstract void removeDerived(long tagId);

  abstract String ownerServiceName();

  abstract CharSequence ownerIntegrationName();

  abstract CharSequence ownerServiceNameSource();

  private TagDerivations table;

  /** Slot bits of every tag a formula has read since the last invalidation. */
  private long depMask;

  /** Whether a formula has read a span field since the last invalidation. */
  private boolean readsFields;

  /** One bit per formula index: run (or suppressed by an explicit value) since invalidation. */
  private long evaluated;

  /** One bit per formula index: running now, to stop cycles. */
  private long evaluating;

  /** The formula whose {@link TagWriter} calls are allowed, or -1. */
  private int current = -1;

  /** The ids of stored values a formula produced; null until the first one. */
  private long[] derivedIds;

  private int derivedCount;
  private long derivedMask;

  DerivedTagState(TagDerivations table) {
    this.table = table;
  }

  TagDerivations table() {
    return table;
  }

  /** Switches to another formula table, undoing everything the old one produced. */
  void reset(TagDerivations table) {
    invalidate();
    this.table = table;
  }

  /** Runs the formula that produces {@code tagId}, if it hasn't run yet. */
  void evaluateFor(long tagId) {
    int index = table.indexOf(tagId);
    if (index >= 0) {
      evaluate(index);
    }
  }

  /** Runs every formula that hasn't run yet, so the store holds every derived tag. */
  void evaluateAll() {
    int size = table.size();
    long all = size == 64 ? -1L : (1L << size) - 1;
    if ((evaluated & all) == all) {
      return;
    }
    for (int i = 0; i < size; ++i) {
      evaluate(i);
    }
  }

  private void evaluate(int index) {
    long bit = 1L << index;
    if (((evaluated | evaluating) & bit) != 0L) {
      return;
    }
    for (long target : table.targets(index)) {
      // An explicit value wins. Recording the target means removing that value later re-decides.
      depMask |= slotBit(target);
      if (storedValue(target) != null && !isDerived(target)) {
        evaluated |= bit;
        return;
      }
    }
    evaluating |= bit;
    int previous = current;
    current = index;
    try {
      table.formula(index).compute(this, this);
    } catch (Throwable t) {
      log.debug("A derived tag formula failed; its tags are left absent", t);
    } finally {
      current = previous;
      evaluating &= ~bit;
      evaluated |= bit;
    }
  }

  /** Called after any write or removal of a known tag by anything other than a formula. */
  void onWrite(long tagId) {
    if (isDerived(tagId)) {
      // An explicit value replaced a derived one: keep it, and undo the formula's other outputs.
      forget(tagId);
      invalidate();
    } else if ((depMask & slotBit(tagId)) != 0L) {
      invalidate();
    }
  }

  /** Called after a span field a formula may read changes. */
  void onFieldWrite() {
    if (readsFields) {
      invalidate();
    }
  }

  boolean isDerived(long tagId) {
    if ((derivedMask & slotBit(tagId)) == 0L) {
      return false;
    }
    for (int i = 0; i < derivedCount; ++i) {
      if (derivedIds[i] == tagId) {
        return true;
      }
    }
    return false;
  }

  private void invalidate() {
    for (int i = 0; i < derivedCount; ++i) {
      removeDerived(derivedIds[i]);
    }
    derivedCount = 0;
    derivedMask = 0L;
    depMask = 0L;
    readsFields = false;
    evaluated = 0L;
  }

  private void remember(long tagId) {
    if (isDerived(tagId)) {
      return;
    }
    if (derivedIds == null) {
      derivedIds = new long[3]; // peer service writes up to three
    } else if (derivedCount == derivedIds.length) {
      derivedIds = Arrays.copyOf(derivedIds, derivedCount << 1);
    }
    derivedIds[derivedCount++] = tagId;
    derivedMask |= slotBit(tagId);
  }

  private void forget(long tagId) {
    for (int i = 0; i < derivedCount; ++i) {
      if (derivedIds[i] == tagId) {
        derivedIds[i] = derivedIds[--derivedCount];
        return;
      }
    }
  }

  // TagReader: every read is a dependency.

  @Override
  public Object get(long tagId) {
    depMask |= slotBit(tagId);
    return visibleValue(tagId);
  }

  @Override
  public String getString(long tagId) {
    Object value = get(tagId);
    return value == null ? null : value.toString();
  }

  @Override
  public boolean isSet(long tagId) {
    return get(tagId) != null;
  }

  @Override
  public String serviceName() {
    readsFields = true;
    return ownerServiceName();
  }

  @Override
  public CharSequence integrationName() {
    readsFields = true;
    return ownerIntegrationName();
  }

  @Override
  public CharSequence serviceNameSource() {
    readsFields = true;
    return ownerServiceNameSource();
  }

  // TagWriter: only the running formula, only its declared targets.

  @Override
  public void set(long tagId, Object value) {
    int index = current;
    if (index < 0) {
      throw new IllegalStateException("a TagWriter is valid only during its formula");
    }
    boolean declared = false;
    for (long target : table.targets(index)) {
      if (target == tagId) {
        declared = true;
        break;
      }
    }
    if (!declared) {
      throw new IllegalStateException(
          "a formula set a tag it didn't declare: " + Long.toHexString(tagId));
    }
    if (value != null) {
      storeDerived(tagId, value);
      remember(tagId);
    }
  }

  /**
   * Runs every formula once for an owner that is done changing, such as a finished span at
   * serialization, when no formula has run before. Outputs go straight into the store and nothing
   * is tracked, so the pass allocates nothing; one instance per thread is reused.
   *
   * <p>An explicit value still wins, a formula may still read another derived tag, and cycles and
   * undeclared outputs are still caught. Only invalidation is dropped, which a finished owner
   * doesn't need.
   */
  abstract static class FinalPass implements TagReader, TagWriter {
    private TagDerivations table;
    private long evaluated;
    private long evaluating;
    private int current = -1;

    /** The owner's stored value, never running a formula. */
    abstract Object storedValue(long tagId);

    /** Stores a formula's output. */
    abstract void storeDerived(long tagId, Object value);

    abstract String ownerServiceName();

    abstract CharSequence ownerIntegrationName();

    abstract CharSequence ownerServiceNameSource();

    /** Runs every formula of {@code table} against the owner this pass is bound to. */
    final void run(TagDerivations table) {
      this.table = table;
      this.evaluated = 0L;
      this.evaluating = 0L;
      this.current = -1;
      try {
        for (int i = 0; i < table.size(); ++i) {
          evaluate(i);
        }
      } finally {
        this.table = null;
      }
    }

    private void evaluate(int index) {
      long bit = 1L << index;
      if (((evaluated | evaluating) & bit) != 0L) {
        return;
      }
      for (long target : table.targets(index)) {
        if (storedValue(target) != null) {
          evaluated |= bit; // an explicit value wins
          return;
        }
      }
      evaluating |= bit;
      int previous = current;
      current = index;
      try {
        table.formula(index).compute(this, this);
      } catch (Throwable t) {
        log.debug("A derived tag formula failed; its tags are left absent", t);
      } finally {
        current = previous;
        evaluating &= ~bit;
        evaluated |= bit;
      }
    }

    @Override
    public final Object get(long tagId) {
      Object value = storedValue(tagId);
      if (value == null && table.mayProduce(tagId)) {
        int index = table.indexOf(tagId);
        if (index >= 0) {
          evaluate(index);
          value = storedValue(tagId);
        }
      }
      return value;
    }

    @Override
    public final String getString(long tagId) {
      Object value = get(tagId);
      return value == null ? null : value.toString();
    }

    @Override
    public final boolean isSet(long tagId) {
      return get(tagId) != null;
    }

    @Override
    public final String serviceName() {
      return ownerServiceName();
    }

    @Override
    public final CharSequence integrationName() {
      return ownerIntegrationName();
    }

    @Override
    public final CharSequence serviceNameSource() {
      return ownerServiceNameSource();
    }

    @Override
    public final void set(long tagId, Object value) {
      int index = current;
      if (index < 0) {
        throw new IllegalStateException("a TagWriter is valid only during its formula");
      }
      boolean declared = false;
      for (long target : table.targets(index)) {
        if (target == tagId) {
          declared = true;
          break;
        }
      }
      if (!declared) {
        throw new IllegalStateException(
            "a formula set a tag it didn't declare: " + Long.toHexString(tagId));
      }
      if (value != null) {
        storeDerived(tagId, value);
      }
    }
  }
}
