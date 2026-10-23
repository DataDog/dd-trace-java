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
 * out (see {@link #spanOccupancy}). The full id is rebuilt from the serial ({@link
 * KnownTagCodec#idOf}) only where a reader asks for it. Values are boxed: a primitive set by id is
 * stored as its wrapper.
 *
 * <p>Point reads that need only the value ({@link #getValue}) build nothing. Those that return a
 * reader or an Entry build their own ({@link #getEntryReader}, {@link #getEntry}), so concurrent
 * reads of a frozen store shared between threads never share state. Only writes answer their prior
 * value with this store's one reused flyweight, valid until the store's next operation (see {@link
 * IdStore}).
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

  /**
   * The reused flyweight a dense store answers with: a known tag's id and value, with no Entry. Its
   * name is resolved from the id only when asked for.
   */
  static class DenseReader implements EntryReader {
    private long tagId;
    private Object value;
    private String tag; // resolved lazily via nameOf on first tag() access

    void set(long tagId, Object value) {
      this.tagId = tagId;
      this.value = value;
      this.tag = null;
    }

    @Override
    public final String tag() {
      String tag = this.tag;
      if (tag == null) {
        tag = KnownTagCodec.nameOf(this.tagId);
        this.tag = tag;
      }
      return tag;
    }

    @Override
    public final long tagId() {
      return this.tagId;
    }

    @Override
    public final byte type() {
      return TagValueConversions.typeOf(this.value);
    }

    @Override
    public final boolean is(byte type) {
      return TagValueConversions.isA(this.value, type);
    }

    @Override
    public final boolean isNumber() {
      return TagValueConversions.isNumber(this.value);
    }

    @Override
    public final boolean isNumericPrimitive() {
      return TagValueConversions.isNumericPrimitive(this.value);
    }

    @Override
    public final boolean isObject() {
      return TagValueConversions.isObject(this.value);
    }

    @Override
    public final boolean booleanValue() {
      return TagValueConversions.toBoolean(this.value);
    }

    @Override
    public final int intValue() {
      return TagValueConversions.toInt(this.value);
    }

    @Override
    public final long longValue() {
      return TagValueConversions.toLong(this.value);
    }

    @Override
    public final float floatValue() {
      return TagValueConversions.toFloat(this.value);
    }

    @Override
    public final double doubleValue() {
      return TagValueConversions.toDouble(this.value);
    }

    @Override
    public final String stringValue() {
      return TagValueConversions.toString(this.value);
    }

    @Override
    public final Object objectValue() {
      return this.value;
    }

    @Override
    public final TagMap.Entry entry() {
      return TagMap.Entry.newAnyEntry(this.tagId, this.value);
    }

    @Override
    public final Map.Entry<String, Object> mapEntry() {
      return this.entry();
    }
  }

  /**
   * Walks the leaf's slots, then each ancestor's, skipping tombstones and shadowed tags. The cursor
   * is its own flyweight: {@link #next} points it at the next visible tag and returns it.
   */
  private static final class DenseCursor extends DenseReader implements Cursor {
    private final DenseIdStore leaf;
    private DenseIdStore level;
    private int index = -1;

    DenseCursor(DenseIdStore leaf) {
      this.leaf = leaf;
      this.level = leaf;
    }

    @Override
    public EntryReader next() {
      DenseIdStore level = this.level;
      while (level != null) {
        for (++this.index; this.index < level.used; ++this.index) {
          if (this.leaf.readIfVisible(level, this.index, this)) {
            return this;
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

  /*
   * The slot masks: a tag's slot (KnownTagCodec.slotOf) maps it to one bit of a long. A bit is a
   * may-be-present hint -- tags that don't usually co-occur share a slot -- so a clear bit is a
   * definite miss and a set bit means "scan". The masks are never cleared by a removal, only
   * widened. The hash store needs none: its buckets are the exact state.
   *
   * Span-level and trace-level tags are colored separately and reuse the same slots, since they
   * are meant to live in separate stores (a span's trace-level tags in the frozen parent it reads
   * through). So each store also records which levels it holds, local and inherited, and a lookup
   * consults the slot bits only for a level that is present: in a store holding one level the word
   * is exact, and a store holding both still works, with a cross-level collision costing a scan.
   */

  /**
   * Slots that may hold a live local span-level entry. Span-level and trace-level tags are colored
   * independently, so their slot numbers overlap; a local root span holds both, so each level keeps
   * its own word and a span-level miss isn't defeated by a trace-level tag in the same slot.
   */
  long spanOccupancy;

  /** Slots that may hold a live local trace-level entry; see {@link #spanOccupancy}. */
  long traceOccupancy;

  /** Slots that may hold a local tombstone, hiding an inherited entry. */
  long tombstoneMask;

  /**
   * Slots an ancestor may hold, fixed at construction: ancestors are frozen. A clear bit lets a
   * local miss skip read-through.
   */
  final long inheritedMask;

  /** The levels ({@link #SPAN_LEVEL}, {@link #TRACE_LEVEL}) of the local tombstones. */
  byte tombstoneLevels;

  /** The levels an ancestor may hold, fixed at construction like {@link #inheritedMask}. */
  final byte inheritedLevels;

  static final int SPAN_LEVEL = 1;
  static final int TRACE_LEVEL = 2;

  /** The frozen parent's id store, or null. */
  final DenseIdStore parent;

  /**
   * The reused flyweight that writes ({@code put}, {@code getAndPut}, {@code remove}) answer their
   * prior value with; built on first use. Only writes use it: a store being written has a single
   * writer, while a frozen store that threads share is only read, and reads build their own.
   */
  private DenseReader reader;

  DenseIdStore(IdStore parent) {
    DenseIdStore dense = (DenseIdStore) parent;
    this.parent = dense;
    if (dense == null) {
      this.inheritedMask = 0L;
      this.inheritedLevels = 0;
    } else {
      this.inheritedMask = dense.spanOccupancy | dense.traceOccupancy | dense.inheritedMask;
      this.inheritedLevels = (byte) (dense.localLevels() | dense.inheritedLevels);
    }
  }

  /** {@code tagId}'s slot bit. */
  static long slotBit(long tagId) {
    return 1L << KnownTagCodec.slotOf(tagId);
  }

  /** {@code tagId}'s level: {@link #SPAN_LEVEL} or {@link #TRACE_LEVEL}, without a branch. */
  static int levelOf(long tagId) {
    return 1 << (int) ((tagId & KnownTagCodec.LEVEL_TRACE) >>> 2);
  }

  /** The occupancy word of {@code level}: a select that folds when the level is constant. */
  long occupancy(int level) {
    return level == TRACE_LEVEL ? this.traceOccupancy : this.spanOccupancy;
  }

  /** The levels of the local entries and tombstones. */
  int localLevels() {
    return (this.spanOccupancy != 0L ? SPAN_LEVEL : 0)
        | (this.traceOccupancy != 0L ? TRACE_LEVEL : 0)
        | this.tombstoneLevels;
  }

  /** Whether the slot {@code bit} may hold a local entry or tombstone of {@code level}. */
  boolean mayHaveLocal(long bit, int level) {
    return (occupancy(level) & bit) != 0L
        || ((tombstoneLevels & level) != 0 && (tombstoneMask & bit) != 0L);
  }

  /** Whether an ancestor may hold a tag of {@code level} in the slot {@code bit}. */
  boolean mayInherit(long bit, int level) {
    return (inheritedLevels & level) != 0 && (inheritedMask & bit) != 0L;
  }

  /** Records a live local entry in the slot {@code bit} of {@code level}. */
  void markLocal(long bit, int level) {
    if (level == TRACE_LEVEL) {
      this.traceOccupancy |= bit;
    } else {
      this.spanOccupancy |= bit;
    }
  }

  /** Records a local tombstone in the slot {@code bit} of {@code level}. */
  void markTombstone(long bit, int level) {
    this.tombstoneMask |= bit;
    this.tombstoneLevels |= (byte) level;
  }

  /**
   * A fresh reader per call, never the store's reused one: point reads can come from several
   * threads at once on a frozen store they share. A short-lived reader is usually scalar-replaced.
   */
  @Override
  public EntryReader getEntryReader(long tagId) {
    Object value = this.getValue(tagId);
    if (value == null) {
      return null;
    }
    DenseReader reader = new DenseReader();
    reader.set(tagId, value);
    return reader;
  }

  @Override
  public TagMap.Entry getEntry(long tagId) {
    Object value = this.getValue(tagId);
    return value == null ? null : TagMap.Entry.newAnyEntry(tagId, value);
  }

  @Override
  public Object getValue(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      throw notKnown(tagId);
    }
    return this.getVisible((char) KnownTagCodec.serialNum(tagId), slotBit(tagId), levelOf(tagId));
  }

  @Override
  public String getString(long tagId) {
    Object value = this.getValue(tagId);
    return value == null ? null : value.toString();
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
    if (value == null) {
      this.remove(tagId); // a null value removes the tag
      return;
    }
    this.store(tagId, value);
  }

  @Override
  public void set(long tagId, CharSequence value) {
    if (value == null) {
      this.remove(tagId); // a null value removes the tag
      return;
    }
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
  public EntryReader put(EntryReader entryReader) {
    Object value = entryReader.objectValue();
    if (value == null) {
      return null; // a reader with nothing to store is a no-op, as for TagMap.set
    }
    long tagId = entryReader.tagId();
    return this.readerOf(tagId, this.store(tagId, value));
  }

  @Override
  public EntryReader getAndPut(EntryReader entryReader) {
    if (entryReader.objectValue() == null) {
      return null; // a reader with nothing to store is a no-op
    }
    long tagId = entryReader.tagId();
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
      this.values[i] = entryReader.objectValue();
    } else {
      prior = this.mayInherit(bit, level) ? this.parent.getVisible(serial, bit, level) : null;
      this.append(serial, bit, level, entryReader.objectValue());
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

  /** The reused flyweight over a write's prior {@code value}, or null when there is none. */
  private EntryReader readerOf(long tagId, Object value) {
    if (value == null) {
      return null;
    }
    DenseReader reader = this.reader;
    if (reader == null) {
      reader = new DenseReader();
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
  private boolean readIfVisible(DenseIdStore level, int i, DenseReader reader) {
    Object value = level.values[i];
    if (value == TOMBSTONE || (level != this && !this.visibleFrom(level, i))) {
      return false;
    }
    reader.set(KnownTagCodec.idOf(level.serials[i]), value);
    return true;
  }

  @Override
  void forEach(Consumer<? super EntryReader> consumer) {
    DenseReader reader = new DenseReader();
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
    DenseReader reader = new DenseReader();
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
    DenseReader reader = new DenseReader();
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
    copy.spanOccupancy = this.spanOccupancy;
    copy.traceOccupancy = this.traceOccupancy;
    copy.tombstoneMask = this.tombstoneMask;
    copy.tombstoneLevels = this.tombstoneLevels;
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
      this.spanOccupancy |= that.spanOccupancy;
      this.traceOccupancy |= that.traceOccupancy;
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
      boolean tombstone = this.values[i] == TOMBSTONE;
      long mask = tombstone ? this.tombstoneMask : this.occupancy(levelOf(tagId));
      if ((mask & slotBit(tagId)) == 0L) {
        throw new IllegalStateException("slot bit not set: " + nameAt(i));
      }
      if (tombstone && (this.tombstoneLevels & levelOf(tagId)) == 0) {
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
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < this.used; ++i) {
      Object value = this.values[i];
      builder
          .append('[')
          .append(i)
          .append("]: ")
          .append(nameAt(i))
          .append('=')
          .append(value == TOMBSTONE ? "<tombstone>" : value)
          .append('\n');
    }
    return builder.toString();
  }
}
