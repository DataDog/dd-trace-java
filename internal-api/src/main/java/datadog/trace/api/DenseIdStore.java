package datadog.trace.api;

import datadog.trace.api.TagMap.EntryReader;
import datadog.trace.api.function.TriConsumer;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The dense {@link IdStore}: known tags in two parallel arrays -- each tag's 16-bit serial and its
 * value -- with no Entry per tag, reading through to the frozen parent's store.
 *
 * <p>A lookup is a linear scan of the serials, skipped when the slot and level masks rule the tag
 * out. The full id is rebuilt from the serial ({@link KnownTagCodec#idOf}) only where a reader asks
 * for it. Values are boxed: a primitive set by id is stored as its wrapper.
 *
 * <p>Point reads that need only the value ({@link #getValue}) build nothing. Those that return an
 * {@link EntryReader} answer with this store's one reused flyweight, valid until the store's next
 * operation (see {@link IdStore}).
 *
 * <p>Removing a tag that hides nothing inherited swaps the last slot into its place. Removing a tag
 * the parent chain also holds stores the shared {@link #TOMBSTONE} as its value instead, and sets
 * its slot in the tombstone mask. A tombstone is never counted, iterated or returned.
 */
final class DenseIdStore extends IdStore {
  /** A value hiding an inherited tag. Never escapes the store. */
  private static final Object TOMBSTONE = new Object();

  /** First capacity, on a store's first tag. */
  static final int INIT_CAP = 12;

  private static final char[] NO_SERIALS = new char[0];
  private static final Object[] NO_VALUES = new Object[0];

  /** Walks the leaf's slots, then each ancestor's, skipping tombstones and shadowed tags. */
  private static final class DenseCursor extends Cursor {
    private final DenseIdStore leaf;
    private DenseIdStore level;
    private int index = -1;
    private final EntryReadingHelper reader = new EntryReadingHelper();

    DenseCursor(DenseIdStore leaf) {
      this.leaf = leaf;
      this.level = leaf;
    }

    @Override
    EntryReader next() {
      DenseIdStore level = this.level;
      while (level != null) {
        for (++this.index; this.index < level.used; ++this.index) {
          if (this.leaf.readIfVisible(level, this.index, this.reader)) {
            return this.reader;
          }
        }
        level = level.parent;
        this.level = level;
        this.index = -1;
      }
      return null;
    }
  }

  /** The serial of each local tag or tombstone, in the first {@link #used} slots. */
  private char[] serials = NO_SERIALS;

  private Object[] values = NO_VALUES;

  /** Slots in use, tombstones included. */
  private int used;

  /** Live local tags, not counting tombstones. */
  private int count;

  /** The frozen parent's id store, or null. */
  final DenseIdStore parent;

  /** The reused flyweight point reads answer with; built on first use. */
  private EntryReadingHelper reader;

  DenseIdStore(IdStore parent) {
    super(parent);
    this.parent = (DenseIdStore) parent;
  }

  @Override
  public EntryReader getEntryReader(long tagId) {
    return this.readerOf(tagId, this.getValue(tagId));
  }

  @Override
  public Object getValue(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    return this.getVisible((char) KnownTagCodec.serialNum(tagId), slotBit(tagId), levelOf(tagId));
  }

  /**
   * The visible value of the tag {@code serial} in the slot {@code bit} of {@code level}, or null.
   */
  private Object getVisible(char serial, long bit, int level) {
    if (this.mayHaveLocal(bit, level)) {
      int i = this.indexOf(serial);
      if (i >= 0) {
        Object value = this.values[i];
        return value == TOMBSTONE ? null : value;
      }
    }
    return this.mayInherit(bit, level) ? this.parent.getVisible(serial, bit, level) : null;
  }

  @Override
  public void set(long tagId, Object value) {
    this.store(tagId, value);
  }

  @Override
  public void set(long tagId, CharSequence value) {
    this.store(tagId, value);
  }

  @Override
  public void set(long tagId, boolean value) {
    this.store(tagId, Boolean.valueOf(value));
  }

  @Override
  public void set(long tagId, int value) {
    this.store(tagId, Integer.valueOf(value));
  }

  @Override
  public void set(long tagId, long value) {
    this.store(tagId, Long.valueOf(value));
  }

  @Override
  public void set(long tagId, float value) {
    this.store(tagId, Float.valueOf(value));
  }

  @Override
  public void set(long tagId, double value) {
    this.store(tagId, Double.valueOf(value));
  }

  @Override
  public EntryReader put(EntryReader known) {
    long tagId = known.tagId();
    return this.readerOf(tagId, this.store(tagId, known.objectValue()));
  }

  @Override
  public EntryReader getAndPut(EntryReader known) {
    long tagId = known.tagId();
    char serial = (char) KnownTagCodec.serialNum(tagId);
    long bit = slotBit(tagId);
    int level = levelOf(tagId);
    int i = this.mayHaveLocal(bit, level) ? this.indexOf(serial) : -1;
    Object prior;
    if (i >= 0) {
      prior = this.values[i];
      if (prior == TOMBSTONE) {
        prior = null; // tombstoned, so not visible
        this.markLocal(bit, level);
        this.count++;
      }
      this.values[i] = known.objectValue();
    } else {
      prior = this.mayInherit(bit, level) ? this.parent.getVisible(serial, bit, level) : null;
      this.append(serial, bit, level, known.objectValue());
    }
    return this.readerOf(tagId, prior);
  }

  @Override
  public EntryReader remove(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    char serial = (char) KnownTagCodec.serialNum(tagId);
    long bit = slotBit(tagId);
    int level = levelOf(tagId);

    int i = this.mayHaveLocal(bit, level) ? this.indexOf(serial) : -1;
    Object local = null;
    if (i >= 0) {
      local = this.values[i];
      if (local == TOMBSTONE) {
        return null; // already removed
      }
    }
    if (this.mayInherit(bit, level)) {
      // Read-through: hide an inherited value with a tombstone. The prior *visible* value is the
      // local one if there was one, otherwise the inherited one.
      Object inherited = this.parent.getVisible(serial, bit, level);
      if (inherited != null) {
        if (i >= 0) {
          this.values[i] = TOMBSTONE;
          this.count--;
        } else {
          this.appendSlot(serial, TOMBSTONE);
        }
        this.markTombstone(bit, level);
        return this.readerOf(tagId, local != null ? local : inherited);
      }
    }
    if (i >= 0) {
      this.removeAt(i);
      this.count--;
    }
    return this.readerOf(tagId, local);
  }

  /** Sets a known tag's value, returning the prior local value, or null. Builds no Entry. */
  private Object store(long tagId, Object value) {
    char serial = (char) KnownTagCodec.serialNum(tagId);
    long bit = slotBit(tagId);
    int level = levelOf(tagId);
    int i = this.mayHaveLocal(bit, level) ? this.indexOf(serial) : -1;
    if (i >= 0) {
      Object prior = this.values[i];
      this.values[i] = value;
      if (prior == TOMBSTONE) {
        this.markLocal(bit, level);
        this.count++; // a tombstone replaced is a removal undone: no prior local value
        return null;
      }
      return prior;
    }
    this.append(serial, bit, level, value);
    return null;
  }

  /** Appends a live local tag. */
  private void append(char serial, long bit, int level, Object value) {
    this.appendSlot(serial, value);
    this.markLocal(bit, level);
    this.count++;
  }

  private void appendSlot(char serial, Object value) {
    int i = this.used;
    if (i == this.serials.length) {
      this.grow();
    }
    this.serials[i] = serial;
    this.values[i] = value;
    this.used = i + 1;
  }

  /** Removes slot {@code i}, swapping the last slot into its place. The masks stay as they were. */
  private void removeAt(int i) {
    int last = --this.used;
    this.serials[i] = this.serials[last];
    this.values[i] = this.values[last];
    this.serials[last] = 0;
    this.values[last] = null;
  }

  /** Index of {@code serial} among the used slots, or -1. */
  private int indexOf(char serial) {
    char[] serials = this.serials;
    for (int i = 0; i < this.used; ++i) {
      if (serials[i] == serial) return i;
    }
    return -1;
  }

  /**
   * Makes room for one more slot: the first from the shared empty arrays at {@link #INIT_CAP}, then
   * doubling, bounded by {@link KnownTagCodec#serialLimit()} -- a store holds each known tag at
   * most once. Out of line, as it is rare after the first.
   */
  private void grow() {
    int length = this.serials.length;
    if (length == 0) {
      this.serials = new char[INIT_CAP];
      this.values = new Object[INIT_CAP];
      return;
    }
    int newCap = Math.max(Math.min(length << 1, KnownTagCodec.serialLimit()), length + 1);
    this.serials = Arrays.copyOf(this.serials, newCap);
    this.values = Arrays.copyOf(this.values, newCap);
  }

  /** The flyweight over {@code value}, or null when there is none. */
  private EntryReader readerOf(long tagId, Object value) {
    if (value == null) {
      return null;
    }
    EntryReadingHelper reader = this.reader;
    if (reader == null) {
      reader = new EntryReadingHelper();
      this.reader = reader;
    }
    reader.set(tagId, value);
    return reader;
  }

  @Override
  int size() {
    return this.parent == null ? this.count : this.count + this.visibleParentCount();
  }

  /** Ancestor tags not shadowed or tombstoned by a nearer level, nearest-level-wins. */
  private int visibleParentCount() {
    int count = 0;
    for (DenseIdStore ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      for (int i = 0; i < ancestor.used; ++i) {
        if (ancestor.values[i] != TOMBSTONE && this.visibleFrom(ancestor, i)) {
          count++;
        }
      }
    }
    return count;
  }

  @Override
  boolean isEmpty() {
    if (this.count != 0) {
      return false;
    }
    DenseIdStore parent = this.parent;
    if (parent == null) {
      return true;
    }
    if (this.tombstoneMask == 0L) {
      return parent.isEmpty(); // nothing hidden: the parent's tags are all visible
    }
    return this.visibleParentCount() == 0;
  }

  @Override
  boolean isDefinitelyEmpty() {
    for (DenseIdStore level = this; level != null; level = level.parent) {
      if (level.count != 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  int estimateSize() {
    int total = 0;
    for (DenseIdStore level = this; level != null; level = level.parent) {
      total += level.count;
    }
    return total;
  }

  @Override
  boolean hasParent() {
    return this.parent != null;
  }

  /**
   * Whether slot {@code i} of the ancestor level {@code fromAncestor} is visible through this leaf:
   * no nearer level holds a value or a tombstone for its tag. A nearer level whose masks rule the
   * slot out is skipped without a scan.
   */
  private boolean visibleFrom(DenseIdStore fromAncestor, int i) {
    char serial = fromAncestor.serials[i];
    long tagId = KnownTagCodec.idOf(serial);
    long bit = slotBit(tagId);
    int level = levelOf(tagId);
    for (DenseIdStore nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      if (nearer.mayHaveLocal(bit, level) && nearer.indexOf(serial) >= 0) {
        return false;
      }
    }
    return true;
  }

  /** Points {@code reader} at slot {@code i} of {@code level}, if it holds a visible tag. */
  private boolean readIfVisible(DenseIdStore level, int i, EntryReadingHelper reader) {
    Object value = level.values[i];
    if (value == TOMBSTONE || (level != this && !this.visibleFrom(level, i))) {
      return false;
    }
    reader.set(KnownTagCodec.idOf(level.serials[i]), value);
    return true;
  }

  @Override
  void forEach(Consumer<? super EntryReader> consumer) {
    EntryReadingHelper reader = new EntryReadingHelper();
    for (DenseIdStore level = this; level != null; level = level.parent) {
      for (int i = 0; i < level.used; ++i) {
        if (this.readIfVisible(level, i, reader)) {
          consumer.accept(reader);
        }
      }
    }
  }

  @Override
  <T> void forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
    EntryReadingHelper reader = new EntryReadingHelper();
    for (DenseIdStore level = this; level != null; level = level.parent) {
      for (int i = 0; i < level.used; ++i) {
        if (this.readIfVisible(level, i, reader)) {
          consumer.accept(thisObj, reader);
        }
      }
    }
  }

  @Override
  <T, U> void forEach(T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
    EntryReadingHelper reader = new EntryReadingHelper();
    for (DenseIdStore level = this; level != null; level = level.parent) {
      for (int i = 0; i < level.used; ++i) {
        if (this.readIfVisible(level, i, reader)) {
          consumer.accept(thisObj, otherObj, reader);
        }
      }
    }
  }

  @Override
  Cursor cursor() {
    return new DenseCursor(this);
  }

  @Override
  IdStore copy() {
    DenseIdStore copy = new DenseIdStore(this.parent);
    if (this.used != 0) {
      copy.serials = Arrays.copyOf(this.serials, this.serials.length);
      copy.values = Arrays.copyOf(this.values, this.values.length);
    }
    copy.used = this.used;
    copy.count = this.count;
    copy.occupancy = this.occupancy;
    copy.tombstoneMask = this.tombstoneMask;
    copy.levels = this.levels;
    return copy;
  }

  @Override
  void putAllLocal(IdStore other) {
    DenseIdStore that = (DenseIdStore) other;
    if (that.count == 0) {
      return;
    }
    if (this.used == 0 && that.used == that.count) {
      // nothing here and no tombstones there: take copies of its arrays
      this.serials = Arrays.copyOf(that.serials, that.serials.length);
      this.values = Arrays.copyOf(that.values, that.values.length);
      this.used = that.used;
      this.count = that.count;
      this.occupancy |= that.occupancy;
      this.levels |= that.levels;
      return;
    }
    for (int i = 0; i < that.used; ++i) {
      Object value = that.values[i];
      if (value != TOMBSTONE) {
        this.store(KnownTagCodec.idOf(that.serials[i]), value);
      }
    }
  }

  @Override
  void fillMapLocal(Map<? super String, Object> map) {
    for (int i = 0; i < this.used; ++i) {
      Object value = this.values[i];
      if (value != TOMBSTONE) {
        map.put(nameAt(i), value);
      }
    }
  }

  @Override
  void fillStringMapLocal(Map<? super String, ? super String> map) {
    for (int i = 0; i < this.used; ++i) {
      Object value = this.values[i];
      if (value != TOMBSTONE) {
        map.put(nameAt(i), TagValueConversions.toString(value));
      }
    }
  }

  private String nameAt(int i) {
    return KnownTagCodec.nameOf(KnownTagCodec.idOf(this.serials[i]));
  }

  @Override
  void checkIntegrity() {
    if (this.used > this.serials.length || this.serials.length != this.values.length) {
      throw new IllegalStateException("incorrect capacity");
    }
    for (int i = 0; i < this.used; ++i) {
      char serial = this.serials[i];
      if (serial == 0 || serial >= KnownTagCodec.serialLimit()) {
        throw new IllegalStateException("not a known serial: " + (int) serial);
      }
      long tagId = KnownTagCodec.idOf(serial);
      long mask = this.values[i] == TOMBSTONE ? this.tombstoneMask : this.occupancy;
      if ((mask & slotBit(tagId)) == 0L) {
        throw new IllegalStateException("slot bit not set: " + nameAt(i));
      }
      if ((this.levels & levelOf(tagId)) == 0) {
        throw new IllegalStateException("level not set: " + nameAt(i));
      }
      if (this.values[i] == null) {
        throw new IllegalStateException("null value: " + nameAt(i));
      }
      for (int j = i + 1; j < this.used; ++j) {
        if (this.serials[j] == serial) {
          throw new IllegalStateException("duplicate tag: " + nameAt(i));
        }
      }
    }
    if (this.count != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
  }

  @Override
  int computeSize() {
    int live = 0;
    for (int i = 0; i < this.used; ++i) {
      if (this.values[i] != TOMBSTONE) live++;
    }
    return live;
  }

  @Override
  String toInternalString() {
    StringBuilder ledger = new StringBuilder();
    for (int i = 0; i < this.used; ++i) {
      Object value = this.values[i];
      ledger
          .append('[')
          .append(i)
          .append("]: ")
          .append(nameAt(i))
          .append('=')
          .append(value == TOMBSTONE ? "<tombstone>" : value)
          .append('\n');
    }
    return ledger.toString();
  }
}
