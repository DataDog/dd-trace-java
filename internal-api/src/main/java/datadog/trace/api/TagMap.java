package datadog.trace.api;

import datadog.trace.api.function.TriConsumer;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A super simple hash map designed for...
 *
 * <ul>
 *   <li>fast copy from one map to another
 *   <li>compatibility with builder idioms
 *   <li>building small maps as fast as possible
 *   <li>storing primitives without boxing
 *   <li>minimal memory footprint
 * </ul>
 *
 * <p>This is mainly accomplished by using immutable entry objects that can reference an object or a
 * primitive. By using immutable entries, the entry objects can be shared between builders & maps
 * freely.
 *
 * <p>This map lacks the ability to mutate an entry via @link {@link Entry#setValue(Object)}.
 * Entries must be replaced by re-setting / re-putting the key which will create a new Entry object.
 *
 * <p>This map also lacks features designed for handling large long lived mutable maps...
 *
 * <ul>
 *   <li>bucket array expansion
 *   <li>adaptive collision
 * </ul>
 */
public final class TagMap implements Map<String, Object>, Iterable<TagMap.EntryReader> {
  /** Immutable empty TagMap - similar to {@link Collections#emptyMap()} */
  // Frozen view over a power-of-two array; the private constructor reads no statics, so this is
  // safe to build directly during TagMap's <clinit>.
  public static final TagMap EMPTY = new TagMap(new Object[1 << 4], 0);

  /** Creates a new mutable TagMap that contains the contents of <code>map</code> */
  public static final TagMap fromMap(@Nonnull Map<String, ?> map) {
    TagMap tagMap = TagMap.create(map.size());
    tagMap.putAll(map);
    return tagMap;
  }

  /** Creates a new immutable TagMap that contains the contents of <code>map</code> */
  public static final TagMap fromMapImmutable(@Nonnull Map<String, ?> map) {
    if (map.isEmpty()) {
      return TagMap.EMPTY;
    } else {
      return fromMap(map).freeze();
    }
  }

  public static final TagMap create() {
    return new TagMap();
  }

  public static final TagMap create(int size) {
    return new TagMap();
  }

  /**
   * Creates a fresh, mutable TagMap that reads through to {@code parent} on local misses. The
   * parent must be frozen and is fixed for the life of the returned map (no re-parenting), so
   * read-through relies on a stable parent rather than an unenforced convention. Level-split phase
   * 1.
   *
   * <p>Parents may themselves have parents: reads and the bulk/union views both walk the full
   * ancestor chain, nearest-level-wins, so a multi-level chain (e.g. baggage over trace tags) is a
   * supported layering, not just a single frozen parent.
   *
   * <p>An empty parent is dropped (treated as no parent): it contributes nothing to read through,
   * and — being frozen — never will, so attaching it would only add read-through cost to every
   * local miss/removal for no benefit.
   */
  public static final TagMap createFromParent(TagMap parent) {
    if (parent != null) {
      if (!parent.frozen) {
        throw new IllegalStateException("read-through parent must be frozen");
      }
      if (parent.isDefinitelyEmpty()) {
        parent = null;
      }
    }
    return new TagMap(parent);
  }

  /** Creates a new TagMap.Ledger */
  public static final Ledger ledger() {
    return new Ledger();
  }

  /** Creates a new TagMap.Ledger which handles <code>size</code> modifications before expansion */
  public static final Ledger ledger(int size) {
    return new Ledger(size);
  }

  public abstract static class EntryChange {
    public static final EntryRemoval newRemoval(String tag) {
      return new EntryRemoval(tag);
    }

    final String tag;

    EntryChange(String tag) {
      this.tag = tag;
    }

    public final String tag() {
      return this.tag;
    }

    public final boolean matches(String tag) {
      return (this.tag == tag) || this.tag.equals(tag);
    }

    public abstract boolean isRemoval();
  }

  public static final class EntryRemoval extends EntryChange {
    EntryRemoval(String tag) {
      super(tag);
    }

    @Override
    public boolean isRemoval() {
      return true;
    }
  }

  public interface EntryReader {
    public static final byte OBJECT = 1;

    /*
     * Non-numeric primitive types
     */
    public static final byte BOOLEAN = 2;
    static final byte CHAR_RESERVED = 3;

    /*
     * Numeric constants - deliberately arranged to allow for checking by using type >= BYTE
     */
    static final byte BYTE_RESERVED = 4;
    static final byte SHORT_RESERVED = 5;
    public static final byte INT = 6;
    public static final byte LONG = 7;
    public static final byte FLOAT = 8;
    public static final byte DOUBLE = 9;

    String tag();

    byte type();

    boolean is(byte type);

    boolean isNumericPrimitive();

    boolean isNumber();

    boolean isObject();

    Object objectValue();

    String stringValue();

    boolean booleanValue();

    int intValue();

    long longValue();

    float floatValue();

    double doubleValue();

    Map.Entry<String, Object> mapEntry();

    Entry entry();
  }

  public static final class Entry extends EntryChange
      implements Map.Entry<String, Object>, EntryReader {
    /*
     * Special value used for Objects that haven't been type checked yet.
     * These objects might be primitive box objects.
     */
    static final byte ANY = 0;

    /**
     * Entry for {@code (tag, value)}, or null when {@code value} is null or an empty {@code
     * CharSequence} -- checked by runtime type, so an empty String passed as {@code Object} skips
     * the same as via the {@link #create(String, CharSequence)} overload.
     */
    @Nullable
    public static final Entry create(@Nonnull String tag, Object value) {
      if (value == null) {
        return null;
      }
      if (value instanceof CharSequence && ((CharSequence) value).length() == 0) {
        return null;
      }
      return TagMap.Entry.newAnyEntry(tag, value);
    }

    /** If value is non-null, returns a new TagMap.Entry If value is null or empty, returns null */
    @Nullable
    public static final Entry create(@Nonnull String tag, CharSequence value) {
      // NOTE: From the static typing, we know that value is not a primitive box

      return (value == null || value.length() == 0)
          ? null
          : TagMap.Entry.newObjectEntry(tag, value);
    }

    public static final Entry create(@Nonnull String tag, boolean value) {
      return TagMap.Entry.newBooleanEntry(tag, value);
    }

    public static final Entry create(@Nonnull String tag, int value) {
      return TagMap.Entry.newIntEntry(tag, value);
    }

    public static final Entry create(@Nonnull String tag, long value) {
      return TagMap.Entry.newLongEntry(tag, value);
    }

    public static final Entry create(@Nonnull String tag, float value) {
      return TagMap.Entry.newFloatEntry(tag, value);
    }

    public static final Entry create(@Nonnull String tag, double value) {
      return TagMap.Entry.newDoubleEntry(tag, value);
    }

    static Entry newAnyEntry(Map.Entry<? extends String, ? extends Object> entry) {
      return newAnyEntry(entry.getKey(), entry.getValue());
    }

    static Entry newAnyEntry(String tag, Object value) {
      // DQH - To keep entry creation (e.g. map changes) as fast as possible,
      // the entry construction is kept as simple as possible.

      // Prior versions of this code did type detection on value to
      // recognize box types but that proved expensive.  So now,
      // the type is recorded as an ANY which is an indicator to do
      // type detection later if need be.
      return new Entry(tag, ANY, 0L, value);
    }

    static Entry newObjectEntry(String tag, Object value) {
      return new Entry(tag, OBJECT, 0, value);
    }

    static Entry newBooleanEntry(String tag, boolean value) {
      return new Entry(tag, BOOLEAN, boolean2Prim(value), Boolean.valueOf(value));
    }

    static Entry newBooleanEntry(String tag, Boolean box) {
      return new Entry(tag, BOOLEAN, boolean2Prim(box.booleanValue()), box);
    }

    static Entry newIntEntry(String tag, int value) {
      return new Entry(tag, INT, int2Prim(value), null);
    }

    static Entry newIntEntry(String tag, Integer box) {
      return new Entry(tag, INT, int2Prim(box.intValue()), box);
    }

    static Entry newLongEntry(String tag, long value) {
      return new Entry(tag, LONG, long2Prim(value), null);
    }

    static Entry newLongEntry(String tag, Long box) {
      return new Entry(tag, LONG, long2Prim(box.longValue()), box);
    }

    static Entry newFloatEntry(String tag, float value) {
      return new Entry(tag, FLOAT, float2Prim(value), null);
    }

    static Entry newFloatEntry(String tag, Float box) {
      return new Entry(tag, FLOAT, float2Prim(box.floatValue()), box);
    }

    static Entry newDoubleEntry(String tag, double value) {
      return new Entry(tag, DOUBLE, double2Prim(value), null);
    }

    static Entry newDoubleEntry(String tag, Double box) {
      return new Entry(tag, DOUBLE, double2Prim(box.doubleValue()), box);
    }

    /*
     * hash is stored in line for fast handling of Entry-s coming from another TagMap
     * However, hash is lazily computed using the same trick as {@link java.lang.String}.
     */
    int lazyTagHash;

    // To optimize construction of Entry around boxed primitives and Object entries,
    // no type checks are done during construction.
    // Any Object entries are initially marked as type ANY, prim set to 0, and the Object put into
    // obj
    // If an ANY entry is later type checked or requested as a primitive, then the ANY will be
    // resolved
    // to the correct type.

    // From the outside perspective, this object remains functionally immutable.
    // However, internally, it is important to remember that this type must be thread safe.
    // That includes multiple threads racing to resolve an ANY entry at the same time.

    // Type and prim cannot use the same trick as hash because during ANY resolution the order of
    // writes is important
    volatile byte rawType;
    volatile long rawPrim;
    volatile Object rawObj;

    volatile String strCache = null;

    private Entry(String tag, byte type, long prim, Object obj) {
      super(tag);
      this.lazyTagHash = 0; // lazily computed

      this.rawType = type;
      this.rawPrim = prim;
      this.rawObj = obj;
    }

    int hash() {
      // If value of hash read in this thread is zero, then hash is computed.
      // hash is not held as a volatile, since this computation can safely be repeated as any time
      int hash = this.lazyTagHash;
      if (hash != 0) return hash;

      hash = _hash(this.tag);
      this.lazyTagHash = hash;
      return hash;
    }

    @Override
    public Entry entry() {
      return this;
    }

    @Override
    public java.util.Map.Entry<String, Object> mapEntry() {
      return this;
    }

    @Override
    public byte type() {
      return this.resolveAny();
    }

    @Override
    public boolean is(byte type) {
      byte curType = this.rawType;
      if (curType == type) {
        return true;
      } else if (curType != ANY) {
        return false;
      } else {
        return (this.resolveAny() == type);
      }
    }

    @Override
    public boolean isNumericPrimitive() {
      byte curType = this.rawType;
      if (_isNumericPrimitive(curType)) {
        return true;
      } else if (curType != ANY) {
        return false;
      } else {
        return _isNumericPrimitive(this.resolveAny());
      }
    }

    @Override
    public boolean isNumber() {
      byte curType = this.rawType;
      return _isNumericPrimitive(curType) || (this.rawObj instanceof Number);
    }

    static boolean _isNumericPrimitive(byte type) {
      return (type >= BYTE_RESERVED);
    }

    private byte resolveAny() {
      byte curType = this.rawType;
      if (curType != ANY) return curType;

      Object value = this.rawObj;
      long prim;
      byte resolvedType;

      if (value instanceof Boolean) {
        Boolean boolValue = (Boolean) value;
        prim = boolean2Prim(boolValue);
        resolvedType = BOOLEAN;
      } else if (value instanceof Integer) {
        Integer intValue = (Integer) value;
        prim = int2Prim(intValue);
        resolvedType = INT;
      } else if (value instanceof Long) {
        Long longValue = (Long) value;
        prim = long2Prim(longValue);
        resolvedType = LONG;
      } else if (value instanceof Float) {
        Float floatValue = (Float) value;
        prim = float2Prim(floatValue);
        resolvedType = FLOAT;
      } else if (value instanceof Double) {
        Double doubleValue = (Double) value;
        prim = double2Prim(doubleValue);
        resolvedType = DOUBLE;
      } else {
        prim = 0;
        resolvedType = OBJECT;
      }

      this._setPrim(resolvedType, prim);

      return resolvedType;
    }

    private void _setPrim(byte type, long prim) {
      // Order is important here, the contract is that prim must be set properly *before*
      // type is set to a non-object type

      this.rawPrim = prim;
      this.rawType = type;
    }

    @Override
    public boolean isObject() {
      return this.is(OBJECT);
    }

    public boolean isRemoval() {
      return false;
    }

    @Override
    public Object objectValue() {
      if (this.rawObj != null) {
        return this.rawObj;
      }

      // This code doesn't need to handle ANY-s.
      // An entry that starts as an ANY will always have this.obj set
      switch (this.rawType) {
        case BOOLEAN:
          this.rawObj = prim2Boolean(this.rawPrim);
          break;

        case INT:
          // Maybe use a wider cache that handles response code???
          this.rawObj = prim2Int(this.rawPrim);
          break;

        case LONG:
          this.rawObj = prim2Long(this.rawPrim);
          break;

        case FLOAT:
          this.rawObj = prim2Float(this.rawPrim);
          break;

        case DOUBLE:
          this.rawObj = prim2Double(this.rawPrim);
          break;

        default:
          // DQH - satisfy spot bugs
          break;
      }

      return this.rawObj;
    }

    @Override
    public boolean booleanValue() {
      byte type = this.rawType;

      if (type == BOOLEAN) {
        return prim2Boolean(this.rawPrim);
      } else if (type == ANY && this.rawObj instanceof Boolean) {
        boolean boolValue = (Boolean) this.rawObj;
        this._setPrim(BOOLEAN, boolean2Prim(boolValue));
        return boolValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.rawPrim;

      switch (resolvedType) {
        case INT:
          return prim2Int(prim) != 0;

        case LONG:
          return prim2Long(prim) != 0L;

        case FLOAT:
          return prim2Float(prim) != 0F;

        case DOUBLE:
          return prim2Double(prim) != 0D;

        case OBJECT:
          return (this.rawObj != null);
      }

      return false;
    }

    @Override
    public int intValue() {
      byte type = this.rawType;

      if (type == INT) {
        return prim2Int(this.rawPrim);
      } else if (type == ANY && this.rawObj instanceof Integer) {
        int intValue = (Integer) this.rawObj;
        this._setPrim(INT, int2Prim(intValue));
        return intValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.rawPrim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1 : 0;

        case LONG:
          return (int) prim2Long(prim);

        case FLOAT:
          return (int) prim2Float(prim);

        case DOUBLE:
          return (int) prim2Double(prim);

        case OBJECT:
          return 0;
      }

      return 0;
    }

    @Override
    public long longValue() {
      byte type = this.rawType;

      if (type == LONG) {
        return prim2Long(this.rawPrim);
      } else if (type == ANY && this.rawObj instanceof Long) {
        long longValue = (Long) this.rawObj;
        this._setPrim(LONG, long2Prim(longValue));
        return longValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.rawPrim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1L : 0L;

        case INT:
          return prim2Int(prim);

        case FLOAT:
          return (long) prim2Float(prim);

        case DOUBLE:
          return (long) prim2Double(prim);

        case OBJECT:
          return 0;
      }

      return 0;
    }

    @Override
    public float floatValue() {
      byte type = this.rawType;

      if (type == FLOAT) {
        return prim2Float(this.rawPrim);
      } else if (type == ANY && this.rawObj instanceof Float) {
        float floatValue = (Float) this.rawObj;
        this._setPrim(FLOAT, float2Prim(floatValue));
        return floatValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.rawPrim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1F : 0F;

        case INT:
          return (float) prim2Int(prim);

        case LONG:
          return (float) prim2Long(prim);

        case DOUBLE:
          return (float) prim2Double(prim);

        case OBJECT:
          return 0F;
      }

      return 0F;
    }

    @Override
    public double doubleValue() {
      byte type = this.rawType;

      if (type == DOUBLE) {
        return prim2Double(this.rawPrim);
      } else if (type == ANY && this.rawObj instanceof Double) {
        double doubleValue = (Double) this.rawObj;
        this._setPrim(DOUBLE, double2Prim(doubleValue));
        return doubleValue;
      }

      // resolution will set prim if necessary
      byte resolvedType = this.resolveAny();
      long prim = this.rawPrim;

      switch (resolvedType) {
        case BOOLEAN:
          return prim2Boolean(prim) ? 1D : 0D;

        case INT:
          return prim2Int(prim);

        case LONG:
          return (double) prim2Long(prim);

        case FLOAT:
          return prim2Float(prim);

        case OBJECT:
          return 0D;
      }

      return 0D;
    }

    @Override
    public String stringValue() {
      String strCache = this.strCache;
      if (strCache != null) {
        return strCache;
      }

      String computeStr = this.computeStringValue();
      this.strCache = computeStr;
      return computeStr;
    }

    private String computeStringValue() {
      // Could do type resolution here,
      // but decided to just fallback to this.obj.toString() for ANY case
      switch (this.rawType) {
        case BOOLEAN:
          return Boolean.toString(prim2Boolean(this.rawPrim));

        case INT:
          return Integer.toString(prim2Int(this.rawPrim));

        case LONG:
          return Long.toString(prim2Long(this.rawPrim));

        case FLOAT:
          return Float.toString(prim2Float(this.rawPrim));

        case DOUBLE:
          return Double.toString(prim2Double(this.rawPrim));

        case OBJECT:
        case ANY:
          return this.rawObj.toString();
      }

      return null;
    }

    @Override
    public String toString() {
      return this.tag() + '=' + this.stringValue();
    }

    /** Deprecated in favor of{@link Entry#tag()} */
    @Deprecated
    @Override
    public String getKey() {
      return this.tag();
    }

    /**
     * Deprecated in favor of typed getters like...
     *
     * <ul>
     *   <li>{@link Entry#objectValue()}
     *   <li>{@link Entry#stringValue()}
     *   <li>{@link Entry#booleanValue()}
     *   <li>...
     * </ul>
     */
    @Deprecated
    @Override
    public Object getValue() {
      return this.objectValue();
    }

    @Deprecated
    @Override
    public Object setValue(Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
      return this.hash();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof TagMap.Entry)) return false;

      TagMap.Entry that = (TagMap.Entry) obj;
      return this.tag.equals(that.tag) && this.objectValue().equals(that.objectValue());
    }

    private static long boolean2Prim(boolean value) {
      return value ? 1L : 0L;
    }

    private static boolean prim2Boolean(long prim) {
      return (prim != 0L);
    }

    private static long int2Prim(int value) {
      return value;
    }

    private static int prim2Int(long prim) {
      return (int) prim;
    }

    private static long long2Prim(long value) {
      return value;
    }

    private static long prim2Long(long prim) {
      return prim;
    }

    private static long float2Prim(float value) {
      return Float.floatToIntBits(value);
    }

    private static float prim2Float(long prim) {
      return Float.intBitsToFloat((int) prim);
    }

    private static long double2Prim(double value) {
      return Double.doubleToRawLongBits(value);
    }

    private static double prim2Double(long prim) {
      return Double.longBitsToDouble(prim);
    }

    static int _hash(String tag) {
      int hash = tag.hashCode();
      return hash == 0 ? 0xDD06 : hash ^ (hash >>> 16);
    }
  }

  /*
   * An in-order ledger of changes to be made to a TagMap.
   * Ledger can also serves as a builder for TagMap-s via build & buildImmutable.
   */
  public static final class Ledger implements Iterable<EntryChange> {
    EntryChange[] entryChanges;
    int nextPos = 0;
    boolean containsRemovals = false;

    private Ledger() {
      this(8);
    }

    private Ledger(int size) {
      this.entryChanges = new EntryChange[size];
    }

    public boolean isDefinitelyEmpty() {
      return (this.nextPos == 0);
    }

    /**
     * Provides the estimated size of the map created by the ledger Doesn't account for overwritten
     * entries or entry removal
     *
     * @return the estimated size of the map created by the ledger
     */
    public int estimateSize() {
      return this.nextPos;
    }

    public boolean containsRemovals() {
      return this.containsRemovals;
    }

    public Ledger set(String tag, Object value) {
      return this.recordEntry(Entry.newAnyEntry(tag, value));
    }

    public Ledger set(String tag, CharSequence value) {
      return this.recordEntry(Entry.newObjectEntry(tag, value));
    }

    public Ledger set(String tag, boolean value) {
      return this.recordEntry(Entry.newBooleanEntry(tag, value));
    }

    public Ledger set(String tag, int value) {
      return this.recordEntry(Entry.newIntEntry(tag, value));
    }

    public Ledger set(String tag, long value) {
      return this.recordEntry(Entry.newLongEntry(tag, value));
    }

    public Ledger set(String tag, float value) {
      return this.recordEntry(Entry.newFloatEntry(tag, value));
    }

    public Ledger set(String tag, double value) {
      return this.recordEntry(Entry.newDoubleEntry(tag, value));
    }

    public Ledger set(Entry entry) {
      return this.recordEntry(entry);
    }

    public Ledger remove(String tag) {
      return this.recordRemoval(EntryChange.newRemoval(tag));
    }

    private Ledger recordEntry(Entry entry) {
      this.recordChange(entry);
      return this;
    }

    private Ledger recordRemoval(EntryRemoval entry) {
      this.recordChange(entry);
      this.containsRemovals = true;

      return this;
    }

    private void recordChange(EntryChange entryChange) {
      if (this.nextPos >= this.entryChanges.length) {
        this.entryChanges = Arrays.copyOf(this.entryChanges, this.entryChanges.length << 1);
      }

      this.entryChanges[this.nextPos++] = entryChange;
    }

    public Ledger smartRemove(String tag) {
      if (this.contains(tag)) {
        this.remove(tag);
      }
      return this;
    }

    private boolean contains(String tag) {
      EntryChange[] thisChanges = this.entryChanges;

      // min is to clamp, so bounds check elimination optimization works
      int lenClamp = Math.min(this.nextPos, thisChanges.length);
      for (int i = 0; i < lenClamp; ++i) {
        if (thisChanges[i].matches(tag)) return true;
      }
      return false;
    }

    /*
     * Just for testing
     */
    Entry findLastEntry(String tag) {
      EntryChange[] thisChanges = this.entryChanges;

      // min is to clamp, so ArrayBoundsCheckElimination optimization works
      int clampLen = Math.min(this.nextPos, thisChanges.length) - 1;
      for (int i = clampLen; i >= 0; --i) {
        EntryChange thisChange = thisChanges[i];
        if (!thisChange.isRemoval() && thisChange.matches(tag)) return (Entry) thisChange;
      }
      return null;
    }

    public void reset() {
      Arrays.fill(this.entryChanges, null);
      this.nextPos = 0;
      this.containsRemovals = false;
    }

    @Override
    public Iterator<EntryChange> iterator() {
      return new IteratorImpl(this.entryChanges, this.nextPos);
    }

    public TagMap build() {
      TagMap map = TagMap.create(this.estimateSize());
      fill(map);
      return map;
    }

    void fill(TagMap map) {
      EntryChange[] entryChanges = this.entryChanges;
      int size = this.nextPos;
      for (int i = 0; i < size && i < entryChanges.length; ++i) {
        EntryChange change = entryChanges[i];

        if (change.isRemoval()) {
          map.remove(change.tag());
        } else {
          map.set((Entry) change);
        }
      }
    }

    public TagMap buildImmutable() {
      if (this.nextPos == 0) {
        return TagMap.EMPTY;
      } else {
        return this.build().freeze();
      }
    }

    static final class IteratorImpl implements Iterator<EntryChange> {
      private final EntryChange[] entryChanges;
      private final int size;

      private int pos;

      IteratorImpl(EntryChange[] entryChanges, int size) {
        this.entryChanges = entryChanges;
        this.size = size;

        this.pos = -1;
      }

      @Override
      public boolean hasNext() {
        return (this.pos + 1 < this.size);
      }

      @Override
      public EntryChange next() {
        if (!this.hasNext()) throw new NoSuchElementException("no next");

        return this.entryChanges[++this.pos];
      }
    }
  }

  /*
   * For memory efficiency, TagMap uses a rather complicated bucket system.
   * <p>
   * When there is only a single Entry in a particular bucket, the Entry is stored into the bucket directly.
   * <p>
   * Because the Entry objects can be shared between multiple TagMaps, the Entry objects cannot
   * directly form a linked list to handle collisions.
   * <p>
   * Instead when multiple entries collide in the same bucket, a BucketGroup is formed to hold multiple entries.
   * But a BucketGroup is only formed when a collision occurs to keep allocation low in the common case of no collisions.
   * <p>
   * For efficiency, BucketGroups are a fixed size, so when a BucketGroup fills up another BucketGroup is formed
   * to hold the additional Entry-s.  And the BucketGroup-s are connected via a linked list instead of the Entry-s.
   * <p>
   * This does introduce some inefficiencies when Entry-s are removed.
   * The assumption is that removals are rare, so BucketGroups are never consolidated.
   * However as a precaution if a BucketGroup becomes completely empty, then that BucketGroup will be
   * removed from the collision chain.
   */

  // Shared immutable empty buckets (all null, length 16). Every map points here until its first
  // custom-tag write copies-on-write to a private array (materializeBuckets), so an all-known /
  // known-heavy map (e.g. the trace-tier read-through parent) allocates ZERO buckets. Length is
  // always 16, so reads need no null guard and read-through bucket alignment (hash & 15) holds.
  private static final Object[] EMPTY_BUCKETS = new Object[1 << 4];

  private Object[] buckets;
  private int size;
  private boolean frozen;

  /**
   * Dense known-tag store (dense-tagmap-design §5). Values for KNOWN tags (those {@link
   * KnownTagCodec#keyOf} resolves to a stored id) live in these INSERTION-ORDERED parallel arrays
   * with NO per-tag {@link Entry} object — the allocation win. Lazily allocated on the first
   * known-tag write ({@code null} until then, so all-unknown maps pay nothing) and grown x2 from
   * {@link #KNOWN_INIT_CAP}. Matched by globalSerial via a linear scan ({@link #knownIndexOf});
   * reads aren't hot, so O(knownCount) is fine and positional indexing is deferred. Dormant until a
   * resolver is registered: {@code keyOf} returns 0, so nothing routes here and production is
   * byte-identical.
   *
   * <p>Disjoint from {@link #buckets} by construction: known-ness is global ({@code keyOf} is
   * deterministic), so a known tag is ALWAYS dense and never bucketed, and vice-versa. That
   * disjointness keeps read-through shadow checks within-region — an ancestor dense entry can only
   * be shadowed by a nearer level's dense entry of the same id, an ancestor bucket entry only by a
   * nearer level's bucket entry — so the bucket read-through chain walk is unchanged and the dense
   * one mirrors it ({@link #parentDenseVisible}).
   *
   * <p>{@link #size} counts bucket entries only; {@link #knownCount} counts dense entries; the
   * local total is {@code size + knownCount}.
   */
  private long[] knownIds;

  private Object[] knownValues;
  private int knownCount;

  /**
   * Two-tier presence filter over the dense store — the fast path that lets a definitely-absent
   * known tag append in O(1) instead of paying the {@link #knownIndexOf} scan (the common per-build
   * insert). Each tag's id carries a declaration coordinate {@code (group-decl, field-decl)} (see
   * {@link KnownTagCodec}); a tag is present ONLY IF its group-decl bit is set in {@link
   * #knownGroupMask} AND its field-decl bit is set in {@link #knownFieldMask}. Either bit clear ⟹
   * definitely absent ⟹ skip the scan.
   *
   * <ul>
   *   <li><b>Tier 1 — group mask:</b> one bit per declaration group ({@code 1L << group-decl}). A
   *       clear group bit proves EVERY tag of that group is absent, so seeding a fresh group (e.g.
   *       a {@code SpanPrototype} bulk insert) skips the scan for the whole group — the bulk-insert
   *       payoff. Disjoint groups across two maps ({@code (a.knownGroupMask & b.knownGroupMask) ==
   *       0}) prove nothing shadows across them, which the read-through shadow check exploits (see
   *       {@link #parentDenseHidden}).
   *   <li><b>Tier 2 — field bloom:</b> when the group bit clashes, fall back to one shared word
   *       over {@code field-decl & 63}. A clear field bit still proves absence; a clash falls
   *       through to the authoritative scan.
   * </ul>
   *
   * Superset semantics: bits are set on every add and NEVER cleared on remove (a stale bit only
   * costs a scan, never a wrong answer), so correctness never depends on the coordinate→bit
   * collision rate — only the fast-path hit rate does. With a single declaration group (today), the
   * group bit is always set once anything is stored, so this degrades gracefully to exactly the
   * tier-2 field bloom. A collision-minimizing coordinate assignment (the tag registry) later only
   * raises the hit rate.
   */
  private long knownGroupMask;

  private long knownFieldMask;

  private static final int KNOWN_INIT_CAP =
      12; // generous per-type max stopgap; exact per-type sizing comes with the tag registry

  /**
   * Optional frozen parent for read-through. When non-null, reads that miss the local buckets fall
   * through to the parent chain, nearest-level-wins (a local entry shadows the parent's, a nearer
   * ancestor shadows a farther one). Parents may themselves have parents, so this is a chain (e.g.
   * baggage layered over trace tags). Must be frozen when attached, so it is safely shareable. Not
   * final only so {@link #clear()} can detach it (to null); it is otherwise fixed at construction
   * and never re-pointed. Package-visible so same-package tests can assert attach/detach directly.
   */
  TagMap parent;

  /**
   * Parent keys removed locally (read-through tombstones). Lazily allocated on the first such
   * removal; {@code null} both means "no tombstones" and serves as the gate that keeps the hot
   * paths untouched. Only meaningful when {@link #parent} != null. A tombstone stops read-through
   * fall-through for its key, so a key removed from a child no longer reads through to the parent.
   * Kept off the bucket structure deliberately — it is shape-agnostic (bare-Entry vs BucketGroup)
   * and rare, so it costs a lazy allocation on removal rather than complicating the hot bucket
   * code.
   */
  private Set<String> removedFromParent;

  public TagMap() {
    this((TagMap) null);
  }

  /**
   * Fresh mutable map that reads through to {@code parent} (may be null). The parent is set here at
   * construction and never re-pointed (only detached to null by {@link #clear()}), so read-through
   * optimizations can treat it as fixed.
   */
  private TagMap(TagMap parent) {
    // Start on the shared empty buckets; materializeBuckets() COWs to a private power-of-two array
    // on the first custom-tag write. All-known maps never allocate buckets.
    this.buckets = EMPTY_BUCKETS;
    this.size = 0;
    this.frozen = false;
    this.parent = parent;
  }

  /** Used for inexpensive immutable */
  private TagMap(Object[] buckets, int size) {
    this.buckets = buckets;
    this.size = size;
    this.frozen = true;
    this.parent = null;
  }

  public boolean isOptimized() {
    return true;
  }

  @Override
  public int size() {
    // Exact (Map contract). Under read-through resolves the union; prefer estimateSize() for hints.
    int local = this.size + this.knownCount; // buckets + dense
    TagMap parent = this.parent;
    return parent == null ? local : local + this.visibleParentCount();
  }

  /**
   * Exact count of ancestor entries not shadowed by a nearer level or tombstoned (the read-through
   * addition). Walks the ancestor chain iteratively, nearest-level-wins.
   */
  private int visibleParentCount() {
    int count = 0;
    for (TagMap ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      // dense entries at this ancestor not shadowed/tombstoned by a nearer level
      long[] ancestorIds = ancestor.knownIds;
      int ancestorKnownCount = ancestor.knownCount;
      for (int i = 0; i < ancestorKnownCount; ++i) {
        if (this.parentDenseVisible(ancestorIds[i], ancestor)) count++;
      }
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          if (parentEntryVisible((Entry) parentBucket, ancestor)) count++;
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor)) count++;
            }
          }
        }
      }
    }
    return count;
  }

  @Override
  public boolean isEmpty() {
    // Exact (Map contract). Under read-through resolves the parent; prefer isDefinitelyEmpty().
    if (this.size != 0 || this.knownCount != 0) {
      return false;
    }
    TagMap parent = this.parent;
    if (parent == null) {
      return true;
    }
    if (this.removedFromParent == null) {
      // no local entries and no tombstones -> empty iff the whole ancestor chain is empty (nothing
      // shadows it). Ancestors are frozen (no tombstones), so exact == definite here.
      return parent.isDefinitelyEmpty();
    }
    // size == 0 with tombstones (rare): empty iff every visible ancestor entry is tombstoned
    return this.visibleParentCount() == 0;
  }

  public boolean isDefinitelyEmpty() {
    // Cheap: empty iff no level in the chain holds a local entry (ignores shadowing/tombstones).
    for (TagMap level = this; level != null; level = level.parent) {
      if (level.size != 0 || level.knownCount != 0) {
        return false;
      }
    }
    return true;
  }

  public int estimateSize() {
    // Upper bound: sum of every level's local size (buckets + dense), ignoring shadowing/removals.
    int total = 0;
    for (TagMap level = this; level != null; level = level.parent) {
      total += level.size + level.knownCount;
    }
    return total;
  }

  @Deprecated
  @Override
  public Object get(Object tag) {
    if (!(tag instanceof String)) return null;

    return this.getObject((String) tag);
  }

  /** Provides the corresponding entry value as an Object - boxing if necessary */
  public Object getObject(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? null : entry.objectValue();
  }

  /** Provides the corresponding entry value as a String - calling toString if necessary */
  public String getString(String tag) {
    Entry entry = this.getEntry(tag);
    return entry == null ? null : entry.stringValue();
  }

  public boolean getBoolean(String tag) {
    return this.getBooleanOrDefault(tag, false);
  }

  public boolean getBooleanOrDefault(String tag, boolean defaultValue) {
    Entry entry = this.getEntry(tag);
    return entry == null ? defaultValue : entry.booleanValue();
  }

  public int getInt(String tag) {
    return getIntOrDefault(tag, 0);
  }

  public int getIntOrDefault(String tag, int defaultValue) {
    Entry entry = this.getEntry(tag);
    return entry == null ? defaultValue : entry.intValue();
  }

  public long getLong(String tag) {
    return this.getLongOrDefault(tag, 0L);
  }

  public long getLongOrDefault(String tag, long defaultValue) {
    Entry entry = this.getEntry(tag);
    return entry == null ? defaultValue : entry.longValue();
  }

  public float getFloat(String tag) {
    return this.getFloatOrDefault(tag, 0F);
  }

  public float getFloatOrDefault(String tag, float defaultValue) {
    Entry entry = this.getEntry(tag);
    return entry == null ? defaultValue : entry.floatValue();
  }

  public double getDouble(String tag) {
    return this.getDoubleOrDefault(tag, 0D);
  }

  public double getDoubleOrDefault(String tag, double defaultValue) {
    Entry entry = this.getEntry(tag);
    return entry == null ? defaultValue : entry.doubleValue();
  }

  @Override
  public boolean containsKey(Object key) {
    if (!(key instanceof String)) return false;

    return (this.getEntry((String) key) != null);
  }

  @Override
  public boolean containsValue(Object value) {
    // This could be optimized - but probably isn't called enough to be worth it
    for (EntryReader entryReader : this) {
      if (entryReader.objectValue().equals(value)) return true;
    }
    return false;
  }

  @Override
  public Set<String> keySet() {
    return new Keys(this);
  }

  public Iterator<String> tagIterator() {
    return new KeysIterator(this);
  }

  @Override
  public Collection<Object> values() {
    return new Values(this);
  }

  public Iterator<Object> valueIterator() {
    return new ValuesIterator(this);
  }

  @Override
  public Set<java.util.Map.Entry<String, Object>> entrySet() {
    return new Entries(this);
  }

  public Entry getEntry(String tag) {
    Entry local = this.getLocalEntry(tag);
    if (local != null) {
      // Local entry shadows the parent (local-wins) — unchanged hot path.
      return local;
    }
    // Read-through: miss locally, defer to the frozen parent. Single-parent in phase 1.
    // The tombstone check lives only here, on the cold miss+parent path — the hot local hit above
    // never touches it.
    TagMap parent = this.parent;
    if (parent == null) {
      return null;
    }
    if (this.removedFromParent != null && this.removedFromParent.contains(tag)) {
      return null; // tombstoned: removed locally, do not read through
    }
    return parent.getEntry(tag);
  }

  /** Looks up an entry in this map's own storage only (dense then buckets) — no read-through. */
  private Entry getLocalEntry(String tag) {
    // Known tags live in the dense store; resolve identity and check there first. keyOf is a no-op
    // (returns 0 -> isStored false) until a resolver is registered, so this is inert in production.
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      Object known = this.knownRawValue(id);
      return known == null ? null : Entry.newAnyEntry(tag, known);
    }
    Object[] thisBuckets = this.buckets;
    int hash = TagMap.Entry._hash(tag);
    return findInBucket(thisBuckets[hash & (thisBuckets.length - 1)], hash, tag);
  }

  /**
   * Finds an entry by hash/tag within a single bucket object (Entry | BucketGroup chain | null).
   */
  private static Entry findInBucket(Object bucket, int hash, String tag) {
    if (bucket instanceof Entry) {
      Entry tagEntry = (Entry) bucket;
      return tagEntry.matches(tag) ? tagEntry : null;
    } else if (bucket instanceof BucketGroup) {
      return ((BucketGroup) bucket).findInChain(hash, tag);
    }
    return null;
  }

  /**
   * Whether an entry that lives at ancestor level {@code fromAncestor} is visible through this
   * leaf: not shadowed and not tombstoned by any nearer level (the leaf, or a closer ancestor).
   * Nearest-level-wins. This mirrors what {@link #getEntry(String)}'s recursion applies as it
   * descends -- each nearer level contributes both its own local entries (shadowing) and its own
   * read-through removals (tombstones). A non-leaf level can carry tombstones too: a map may remove
   * an inherited key and then itself be frozen and reused as a parent. Exploits universal hashing —
   * by {@code _hash} the only local entry that could shadow {@code parentEntry} at a level is in
   * that level's same-index bucket, so each nearer level is probed at one bucket using {@code
   * parentEntry}'s cached hash (no re-hash, no full-map probe).
   */
  private boolean parentEntryVisible(Entry parentEntry, TagMap fromAncestor) {
    int hash = parentEntry.hash();
    String tag = parentEntry.tag;
    for (TagMap nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      // tombstoned by a nearer level (its own read-through removal hides the deeper entry)
      if (nearer.removedFromParent != null && nearer.removedFromParent.contains(tag)) {
        return false;
      }
      // shadowed by a nearer level's local entry
      Object[] nearerBuckets = nearer.buckets;
      Object nearerBucket = nearerBuckets[hash & (nearerBuckets.length - 1)];
      if (findInBucket(nearerBucket, hash, tag) != null) {
        return false;
      }
    }
    return true;
  }

  // ---- dense known-tag store (see the knownIds field doc)
  // ----------------------------------------

  /**
   * Linear scan of the dense store for {@code tagId}, returning its index or -1. Ids are canonical
   * (the only way one enters is {@link KnownTagCodec#keyOf} or a {@code KnownTags} constant, both
   * canonical), so a full {@code long} compare is exact and cheaper than extracting globalSerial.
   */
  private int knownIndexOf(long tagId) {
    long[] ids = this.knownIds;
    int n = this.knownCount;
    for (int i = 0; i < n; ++i) {
      if (ids[i] == tagId) return i;
    }
    return -1;
  }

  private void ensureKnownCapacity() {
    if (this.knownIds == null) {
      this.knownIds = new long[KNOWN_INIT_CAP];
      this.knownValues = new Object[KNOWN_INIT_CAP];
    } else if (this.knownCount == this.knownIds.length) {
      int newCap = this.knownIds.length << 1;
      this.knownIds = Arrays.copyOf(this.knownIds, newCap);
      this.knownValues = Arrays.copyOf(this.knownValues, newCap);
    }
  }

  /**
   * Tier-1 presence bit for {@code tagId}: {@code 1L << group-decl} (group-decl is 6 bits ≤ 63).
   */
  private static long knownGroupBit(long tagId) {
    return 1L << KnownTagCodec.groupDecl(tagId);
  }

  /**
   * Tier-2 presence bit for {@code tagId}: {@code field-decl} folded into one shared word ({@code
   * field-decl & 63}). Crude to start; a collision-minimizing coordinate assignment (the tag
   * registry) later only raises the hit rate — the scan stays authoritative.
   */
  private static long knownFieldBit(long tagId) {
    return 1L << (KnownTagCodec.fieldDecl(tagId) & 63);
  }

  /**
   * Whether {@code tagId} MAY be present in the dense store (both tiers say maybe), vs DEFINITELY
   * absent (either tier's bit is clear). Group bit checked first so a fresh group short-circuits
   * before touching the field bloom.
   */
  private boolean knownMaybePresent(long tagId) {
    return (this.knownGroupMask & knownGroupBit(tagId)) != 0
        && (this.knownFieldMask & knownFieldBit(tagId)) != 0;
  }

  /**
   * Stores a known tag's value densely (no {@link Entry} alloc). Overwrites in place when present
   * (returning the prior value materialized as an Entry, per the {@code Map} contract — usually
   * discarded by {@code set}); otherwise appends, growing x2 as needed. The two-tier presence
   * filter skips the {@link #knownIndexOf} scan when the tag is definitely absent (the common
   * per-build case), so an append is O(1) instead of O(n).
   */
  private Entry putKnownValue(long tagId, Object value) {
    long groupBit = knownGroupBit(tagId);
    long fieldBit = knownFieldBit(tagId);
    // maybe present only if BOTH tiers say so; either bit clear ⟹ definitely absent ⟹ append
    if ((this.knownGroupMask & groupBit) != 0 && (this.knownFieldMask & fieldBit) != 0) {
      int i = this.knownIndexOf(tagId);
      if (i >= 0) {
        Object prior = this.knownValues[i];
        this.knownValues[i] = value;
        return materializeKnown(tagId, prior);
      }
      // filter false positive (coordinate collision) -> fall through to append
    }
    this.ensureKnownCapacity();
    int slot = this.knownCount++;
    this.knownIds[slot] = tagId;
    this.knownValues[slot] = value;
    this.knownGroupMask |= groupBit;
    this.knownFieldMask |= fieldBit;
    return null;
  }

  /** Raw dense value for {@code tagId}, or {@code null} when absent (no Entry, no boxing). */
  private Object knownRawValue(long tagId) {
    if (!this.knownMaybePresent(tagId)) return null; // definitely absent, no scan
    int i = this.knownIndexOf(tagId);
    return i < 0 ? null : this.knownValues[i];
  }

  /**
   * Removes a known tag from the dense store (swap-with-last), returning the prior Entry or null.
   */
  private Entry removeKnown(long tagId) {
    if (!this.knownMaybePresent(tagId)) return null; // definitely absent
    int i = this.knownIndexOf(tagId);
    if (i < 0) return null;
    Object prior = this.knownValues[i];
    int last = --this.knownCount;
    this.knownIds[i] = this.knownIds[last];
    this.knownValues[i] = this.knownValues[last];
    this.knownIds[last] = 0L;
    this.knownValues[last] = null;
    // knownGroupMask/knownFieldMask intentionally NOT cleared: a stale-set bit only costs a scan;
    // clearing could drop a bit still shared (via collision) by a present id -> false negative.
    return materializeKnown(tagId, prior);
  }

  /** Materializes a transient Entry for a dense (id, value) pair — only on explicit get/iterate. */
  private static Entry materializeKnown(long tagId, Object value) {
    return Entry.newAnyEntry(KnownTagCodec.nameOf(tagId), value);
  }

  /**
   * Whether an ancestor dense entry ({@code tagId}, declared at level {@code fromAncestor}) is
   * visible from this leaf under read-through: not shadowed by a nearer level's dense entry of the
   * same id and not tombstoned by a nearer level. Chain-aware mirror of {@link #parentEntryVisible}
   * for the dense store. (Disjointness: a known tag never buckets, so no bucket shadow check is
   * needed.)
   */
  private boolean parentDenseVisible(long tagId, TagMap fromAncestor) {
    String tag = null; // resolved lazily, only if a nearer level carries tombstones
    for (TagMap nearer = this; nearer != fromAncestor; nearer = nearer.parent) {
      // shadowed by a nearer dense entry — the two-tier filter prunes the scan when definitely
      // absent, so a nearer level with disjoint groups never pays a scan here (read-through win)
      if (nearer.knownMaybePresent(tagId) && nearer.knownIndexOf(tagId) >= 0) return false;
      if (nearer.removedFromParent != null) {
        if (tag == null) tag = KnownTagCodec.nameOf(tagId);
        if (nearer.removedFromParent.contains(tag)) return false; // tombstoned by a nearer level
      }
    }
    return true;
  }

  @Deprecated
  @Override
  public Object put(@Nonnull String tag, Object value) {
    TagMap.Entry entry = this.getAndSet(tag, value);
    return entry == null ? null : entry.objectValue();
  }

  /** A null reader (or a reader with no entry) is a no-op. */
  public void set(@Nullable TagMap.EntryReader newEntryReader) {
    if (newEntryReader == null) {
      return;
    }
    Entry entry = newEntryReader.entry();
    if (entry != null) {
      this.putEntry(entry);
    }
  }

  // The set(String, ...) family resolves keyOf FIRST: a known tag stores its value densely with no
  // Entry (boxing the primitive only on that branch) and no parent-fallback lookup (set discards
  // the prior value); a custom tag takes the typed bucket insert (no boxing for primitives).
  public void set(@Nonnull String tag, @Nonnull Object value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, value);
    } else {
      this.putBucketEntry(Entry.newAnyEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, @Nonnull CharSequence value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, value);
    } else {
      this.putBucketEntry(Entry.newObjectEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, boolean value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, Boolean.valueOf(value));
    } else {
      this.putBucketEntry(Entry.newBooleanEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, int value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, Integer.valueOf(value));
    } else {
      this.putBucketEntry(Entry.newIntEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, long value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, Long.valueOf(value));
    } else {
      this.putBucketEntry(Entry.newLongEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, float value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, Float.valueOf(value));
    } else {
      this.putBucketEntry(Entry.newFloatEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, double value) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      this.putKnownLocal(id, tag, Double.valueOf(value));
    } else {
      this.putBucketEntry(Entry.newDoubleEntry(tag, value));
    }
  }

  /**
   * Places an Entry directly into the map, avoiding a new Entry allocation. Null-tolerant: a null
   * {@code newEntry} is a no-op returning null, so an Entry producer (e.g. {@link
   * Entry#create(String, Object)} for a null/empty value) can emit "no tag" without the caller
   * filtering. Contrast the strict {@link Nonnull} {@code set(String, value)} setters.
   */
  public Entry getAndSet(@Nullable Entry newEntry) {
    if (newEntry == null) {
      return null;
    }
    return this.getAndSetWithFallback(newEntry);
  }

  /**
   * Local insert (via {@link #putEntry}) plus the read-through parent fallback for the prior
   * visible value (Map contract). When no local entry was replaced and the key was not tombstoned,
   * the prior visible value is the nearest ancestor's, resolved through {@link #getEntry} (which
   * handles both dense known tags and bucketed custom tags). Shared by {@link #getAndSet(Entry)}
   * and the {@code getAndSet(String, ...)} overloads.
   */
  private Entry getAndSetWithFallback(@Nonnull Entry newEntry) {
    // Capture whether the key was tombstoned BEFORE putEntry clears it: a tombstoned key had no
    // visible prior value (it was removed), so getAndSet must report null rather than the parent's.
    boolean wasTombstoned =
        this.removedFromParent != null && this.removedFromParent.contains(newEntry.tag);

    Entry priorLocal = this.putEntry(newEntry);
    if (priorLocal != null) {
      return priorLocal; // replaced a local entry -> that is the prior value
    }
    // No local entry was replaced. The prior visible value, if any, was the parent's -- unless the
    // key was tombstoned (then it was not visible). set(...) skips this via putEntry (no prior).
    if (wasTombstoned || this.parent == null) {
      return null;
    }
    return this.parent.getEntry(newEntry.tag);
  }

  /**
   * Inserts or replaces a local entry, returning the replaced local Entry (or null if none). Does
   * NOT consult the read-through parent -- the {@code set(...)} methods use this so they never pay
   * for a prior-value lookup they discard; {@link #getAndSetWithFallback} layers the parent
   * fallback on top. Routes a known tag to the dense store, a custom tag to the hash buckets.
   */
  private Entry putEntry(@Nonnull Entry newEntry) {
    long id = KnownTagCodec.keyOf(newEntry.tag);
    if (KnownTagCodec.isStored(id)) {
      return this.putKnownLocal(id, newEntry.tag, newEntry.objectValue());
    }
    return this.putBucketEntry(newEntry);
  }

  /**
   * Stores a known tag's value densely with NO Entry retained (the alloc win) and NO parent
   * fallback — the local-only counterpart used by {@code set} and {@link #putEntry}. Returns the
   * prior LOCAL dense value materialized as an Entry (Map contract); usually discarded.
   */
  private Entry putKnownLocal(long id, String tag, Object value) {
    this.checkWriteAccess();
    if (this.removedFromParent != null) {
      this.removedFromParent.remove(tag);
    }
    return this.putKnownValue(id, value);
  }

  /** Copy-on-write the shared empty buckets to a private array on the first bucket write. */
  private Object[] materializeBuckets() {
    Object[] b = this.buckets;
    if (b == EMPTY_BUCKETS) {
      b = new Object[1 << 4];
      this.buckets = b;
    }
    return b;
  }

  /**
   * Stores an entry in the hash buckets — the unknown/custom-tag local path (no parent fallback).
   */
  private Entry putBucketEntry(@Nonnull Entry newEntry) {
    this.checkWriteAccess();

    // Re-setting a key clears any read-through tombstone for it (the new value overrides the
    // removal). Gated on the lazy field, so this is a no-op for the common no-tombstone case.
    if (this.removedFromParent != null) {
      this.removedFromParent.remove(newEntry.tag);
    }

    Object[] thisBuckets = this.materializeBuckets();

    int newHash = newEntry.hash();
    int bucketIndex = newHash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    if (bucket == null) {
      thisBuckets[bucketIndex] = newEntry;

      this.size += 1;
      return null;
    } else if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.matches(newEntry.tag)) {
        thisBuckets[bucketIndex] = newEntry;

        // replaced existing entry - no size change
        return existingEntry;
      } else {
        thisBuckets[bucketIndex] =
            new BucketGroup(existingEntry.hash(), existingEntry, newHash, newEntry);

        this.size += 1;
        return null;
      }
    } else if (bucket instanceof BucketGroup) {
      BucketGroup lastGroup = (BucketGroup) bucket;

      BucketGroup containingGroup = lastGroup.findContainingGroupInChain(newHash, newEntry.tag);
      if (containingGroup != null) {
        // replaced existing entry - no size change
        return containingGroup._replace(newHash, newEntry);
      }

      if (!lastGroup.insertInChain(newHash, newEntry)) {
        thisBuckets[bucketIndex] = new BucketGroup(newHash, newEntry, lastGroup);
      }
      this.size += 1;
      return null;
    }

    // unreachable
    return null;
  }

  // Each getAndSet(String, ...) builds the typed Entry (no boxing for primitives) then funnels
  // through getAndSetWithFallback, which routes a known tag to the dense store (dropping the Entry)
  // and a custom tag to the buckets, layering the read-through parent fallback on top. The Entry-
  // free hot path is set(String, ...), which discards the prior value; getAndSet returns it.
  public Entry getAndSet(@Nonnull String tag, Object value) {
    return this.getAndSetWithFallback(Entry.newAnyEntry(tag, value));
  }

  public Entry getAndSet(@Nonnull String tag, CharSequence value) {
    return this.getAndSetWithFallback(Entry.newObjectEntry(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, boolean value) {
    return this.getAndSetWithFallback(Entry.newBooleanEntry(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, int value) {
    return this.getAndSetWithFallback(Entry.newIntEntry(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, long value) {
    return this.getAndSetWithFallback(Entry.newLongEntry(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, float value) {
    return this.getAndSetWithFallback(Entry.newFloatEntry(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, double value) {
    return this.getAndSetWithFallback(Entry.newDoubleEntry(tag, value));
  }

  public void putAll(Map<? extends String, ? extends Object> map) {
    this.checkWriteAccess();

    if (map instanceof TagMap) {
      this.putAllOptimizedMap((TagMap) map);
    } else {
      this.putAllUnoptimizedMap(map);
    }
  }

  private void putAllUnoptimizedMap(Map<? extends String, ? extends Object> that) {
    for (Map.Entry<? extends String, ?> entry : that.entrySet()) {
      // use set which returns a prior Entry rather put which may box a prior primitive value
      this.set(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Similar to {@link Map#putAll(Map)} but optimized to quickly copy from one TagMap to another
   *
   * <p>For optimized TagMaps, this method takes advantage of the consistent TagMap layout to
   * quickly handle each bucket. And similar to {@link TagMap#getAndSet(Entry)} this method shares
   * Entry objects from the source TagMap
   */
  public void putAll(TagMap that) {
    this.checkWriteAccess();

    this.putAllOptimizedMap(that);
  }

  private void putAllOptimizedMap(TagMap that) {
    if (that.parent != null) {
      // read-through source: the bucket-copy paths below only see that's local entries, so they
      // would drop entries visible only through that's ancestor chain (and ignore its tombstones).
      // Union-copy the full visible set instead -- still shares the source Entry objects.
      that.forEach(this, (self, entry) -> self.set(entry));
      return;
    }
    // "empty" must consider BOTH local regions — a map with only dense entries has size == 0 but is
    // not empty, and putAllIntoEmptyMap would clobber its dense store.
    if (this.size == 0 && this.knownCount == 0) {
      this.putAllIntoEmptyMap(that);
    } else {
      this.putAllMerge(that);
    }
  }

  private void putAllMerge(TagMap that) {
    // COW our buckets only if the source has bucket entries to merge in; otherwise the loop below
    // writes nothing and the shared empty buckets stay shared.
    Object[] thisBuckets = (that.size > 0) ? this.materializeBuckets() : this.buckets;
    Object[] thatBuckets = that.buckets;

    // Since TagMap-s don't support expansion, buckets are perfectly aligned
    // Check against both thisBuckets.length && thatBuckets.length is to help the JIT do bound check
    // elimination
    for (int i = 0; i < thisBuckets.length && i < thatBuckets.length; ++i) {
      Object thatBucket = thatBuckets[i];

      // if nothing incoming, nothing to do
      if (thatBucket == null) continue;

      Object thisBucket = thisBuckets[i];
      if (thisBucket == null) {
        // This bucket is null, easy case
        // Either copy over the sole entry or clone the BucketGroup chain

        if (thatBucket instanceof Entry) {
          thisBuckets[i] = thatBucket;
          this.size += 1;
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          BucketGroup thisNewGroup = thatGroup.cloneChain();
          thisBuckets[i] = thisNewGroup;
          this.size += thisNewGroup.sizeInChain();
        }
      } else if (thisBucket instanceof Entry) {
        // This bucket is a single entry, medium complexity case
        // If other side is an Entry - just merge the entries into a bucket
        // If other side is a BucketGroup - then clone the group and insert the entry normally into
        // the cloned group

        Entry thisEntry = (Entry) thisBucket;
        int thisHash = thisEntry.hash();

        if (thatBucket instanceof Entry) {
          Entry thatEntry = (Entry) thatBucket;
          int thatHash = thatEntry.hash();

          if (thisHash == thatHash && thisEntry.matches(thatEntry.tag())) {
            thisBuckets[i] = thatEntry;
            // replacing entry, no size change
          } else {
            thisBuckets[i] =
                new BucketGroup(
                    thisHash, thisEntry,
                    thatHash, thatEntry);
            this.size += 1;
          }
        } else if (thatBucket instanceof BucketGroup) {
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Clone the other group, then place this entry into that group
          BucketGroup thisNewGroup = thatGroup.cloneChain();
          int thisNewGroupSize = thisNewGroup.sizeInChain();

          Entry incomingEntry = thisNewGroup.findInChain(thisHash, thisEntry.tag());
          if (incomingEntry != null) {
            // there's already an entry w/ the same tag from the incoming TagMap
            // incoming entry clobbers the existing try, so we're done
            thisBuckets[i] = thisNewGroup;

            // overlapping group - subtract one for clobbered existing entry
            this.size += thisNewGroupSize - 1;
          } else if (thisNewGroup.insertInChain(thisHash, thisEntry)) {
            // able to add thisEntry into the existing groups
            thisBuckets[i] = thisNewGroup;

            // non overlapping group - existing entry already accounted for in this.size
            this.size += thisNewGroupSize;
          } else {
            // unable to add into the existing groups
            thisBuckets[i] = new BucketGroup(thisHash, thisEntry, thisNewGroup);

            // non overlapping group - existing entry already accounted for in this.size
            this.size += thisNewGroupSize;
          }
        }
      } else if (thisBucket instanceof BucketGroup) {
        // This bucket is a BucketGroup, medium to hard case
        // If the other side is an entry, just normal insertion procedure - no cloning required
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        if (thatBucket instanceof Entry) {
          Entry thatEntry = (Entry) thatBucket;
          int thatHash = thatEntry.hash();

          if (thisGroup.replaceInChain(thatHash, thatEntry) != null) {
            // replaced existing entry no size change
          } else if (thisGroup.insertInChain(thatHash, thatEntry)) {
            this.size += 1;
          } else {
            thisBuckets[i] = new BucketGroup(thatHash, thatEntry, thisGroup);
            this.size += 1;
          }
        } else if (thatBucket instanceof BucketGroup) {
          // Most complicated case - need to walk that bucket group chain and update this chain
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          // Taking the easy / expensive way out for updating size
          int thisPrevGroupSize = thisGroup.sizeInChain();

          BucketGroup thisNewGroup = thisGroup.replaceOrInsertAllInChain(thatGroup);
          int thisNewGroupSize = thisNewGroup.sizeInChain();

          thisBuckets[i] = thisNewGroup;
          this.size += (thisNewGroupSize - thisPrevGroupSize);
        }
      }
    }

    // merge the source's dense known-tag entries; incoming clobbers existing (same as buckets)
    for (int i = 0; i < that.knownCount; ++i) {
      this.putKnownValue(that.knownIds[i], that.knownValues[i]);
    }
  }

  /*
   * Specially optimized version of putAll for the common case of destination map being empty
   */
  private void putAllIntoEmptyMap(TagMap that) {
    // Only copy buckets (and COW ours) when the source actually has bucket entries; an all-known
    // source leaves us on the shared empty buckets.
    if (that.size > 0) {
      Object[] thisBuckets = this.materializeBuckets();
      Object[] thatBuckets = that.buckets;

      // Check against both thisBuckets.length && thatBuckets.length is to help the JIT do bound
      // check elimination
      for (int i = 0; i < thisBuckets.length && i < thatBuckets.length; ++i) {
        Object thatBucket = thatBuckets[i];

        // faster to explicitly null check first, then do instanceof
        if (thatBucket == null) {
          // do nothing
        } else if (thatBucket instanceof BucketGroup) {
          // if it is a BucketGroup, then need to clone
          BucketGroup thatGroup = (BucketGroup) thatBucket;

          thisBuckets[i] = thatGroup.cloneChain();
        } else { // if ( thatBucket instanceof Entry )
          thisBuckets[i] = thatBucket;
        }
      }
      this.size = that.size;
    }

    // clone the dense known-tag store (values are immutable boxes/objects -> safe to share refs)
    if (that.knownCount > 0) {
      this.knownIds = Arrays.copyOf(that.knownIds, that.knownIds.length);
      this.knownValues = Arrays.copyOf(that.knownValues, that.knownValues.length);
      this.knownCount = that.knownCount;
      this.knownGroupMask = that.knownGroupMask;
      this.knownFieldMask = that.knownFieldMask;
    }
  }

  public void fillMap(Map<? super String, Object> map) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        map.put(thisEntry.tag, thisEntry.objectValue());
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.fillMapFromChain(map);
      }
    }
    for (int i = 0; i < this.knownCount; ++i) {
      map.put(KnownTagCodec.nameOf(this.knownIds[i]), this.knownValues[i]);
    }
  }

  public void fillStringMap(Map<? super String, ? super String> stringMap) {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        stringMap.put(thisEntry.tag, thisEntry.stringValue());
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.fillStringMapFromChain(stringMap);
      }
    }
    for (int i = 0; i < this.knownCount; ++i) {
      stringMap.put(
          KnownTagCodec.nameOf(this.knownIds[i]),
          TagValueConversions.toString(this.knownValues[i]));
    }
  }

  @Override
  public Object remove(Object tag) {
    if (!(tag instanceof String)) return null;

    Entry entry = this.getAndRemove((String) tag);
    return entry == null ? null : entry.objectValue();
  }

  public boolean remove(String tag) {
    return (this.getAndRemove(tag) != null);
  }

  public Entry getAndRemove(String tag) {
    this.checkWriteAccess();

    Entry localRemoved = this.removeLocal(tag);

    TagMap parent = this.parent;
    if (parent != null) {
      // Read-through: if the parent still exposes this key, removing it must also hide it from
      // fall-through — install a tombstone. The prior *visible* value (Map.remove contract) is the
      // local entry if there was one, otherwise the parent's (which we now hide). Single-parent in
      // phase 1; rare path (only when removing a parent-exposed key).
      boolean alreadyTombstoned =
          this.removedFromParent != null && this.removedFromParent.contains(tag);
      if (!alreadyTombstoned) {
        Entry parentEntry = parent.getEntry(tag);
        if (parentEntry != null) {
          if (this.removedFromParent == null) {
            this.removedFromParent = new HashSet<>();
          }
          this.removedFromParent.add(tag);
          return localRemoved != null ? localRemoved : parentEntry;
        }
      }
    }
    return localRemoved;
  }

  /** Removes an entry from this map's own storage only — no parent/tombstone handling. */
  private Entry removeLocal(String tag) {
    long id = KnownTagCodec.keyOf(tag);
    if (KnownTagCodec.isStored(id)) {
      return this.removeKnown(id);
    }

    Object[] thisBuckets = this.buckets;

    int hash = TagMap.Entry._hash(tag);
    int bucketIndex = hash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    // null bucket case - do nothing
    if (bucket instanceof Entry) {
      Entry existingEntry = (Entry) bucket;
      if (existingEntry.matches(tag)) {
        thisBuckets[bucketIndex] = null;

        this.size -= 1;
        return existingEntry;
      } else {
        return null;
      }
    } else if (bucket instanceof BucketGroup) {
      BucketGroup lastGroup = (BucketGroup) bucket;

      BucketGroup containingGroup = lastGroup.findContainingGroupInChain(hash, tag);
      if (containingGroup == null) {
        return null;
      }

      Entry existingEntry = containingGroup._remove(hash, tag);
      if (containingGroup._isEmpty()) {
        this.buckets[bucketIndex] = lastGroup.removeGroupInChain(containingGroup);
      }

      this.size -= 1;
      return existingEntry;
    }
    return null;
  }

  public TagMap copy() {
    // Construct with the same (frozen, shared) parent up front — the parent is fixed at
    // construction. putAll then clones this map's own (local) buckets + size. The copy stays
    // independently mutable (writes land on its local buckets, never the shared parent).
    TagMap copy = new TagMap(this.parent);
    copy.putAllIntoEmptyMap(this);
    if (this.removedFromParent != null) {
      copy.removedFromParent = new HashSet<>(this.removedFromParent);
    }
    return copy;
  }

  public TagMap immutableCopy() {
    if (this.frozen) {
      return this;
    } else {
      return this.copy().freeze();
    }
  }

  @Override
  public Iterator<EntryReader> iterator() {
    return new EntryReaderIterator(this);
  }

  public Stream<EntryReader> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Override
  public void forEach(Consumer<? super TagMap.EntryReader> consumer) {
    // local dense known tags via a reused flyweight (no per-entry Entry alloc — the serialize win)
    if (this.knownCount > 0) {
      EntryReadingHelper reader = new EntryReadingHelper();
      for (int i = 0; i < this.knownCount; ++i) {
        reader.set(KnownTagCodec.nameOf(this.knownIds[i]), this.knownValues[i]);
        consumer.accept(reader);
      }
    }

    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        consumer.accept(thisEntry);
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.forEachInChain(consumer);
      }
    }

    // read-through: parent entries not shadowed locally or tombstoned. Kept out of line so the
    // common parent == null path stays byte-identical to before (small / inlinable).
    if (this.parent != null) {
      this.forEachParent(consumer);
    }
  }

  private void forEachParent(Consumer<? super TagMap.EntryReader> consumer) {
    // Walk the ancestor chain, nearest first. Each entry is emitted once, by the nearest level that
    // defines its key, when not shadowed by a nearer level and not tombstoned. Dense known tags are
    // emitted via a reused flyweight (no per-entry Entry alloc — the serialize win).
    EntryReadingHelper reader = null;
    for (TagMap ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      long[] ancestorIds = ancestor.knownIds;
      int ancestorKnownCount = ancestor.knownCount;
      if (ancestorKnownCount > 0) {
        Object[] ancestorValues = ancestor.knownValues;
        if (reader == null) reader = new EntryReadingHelper();
        for (int i = 0; i < ancestorKnownCount; ++i) {
          long id = ancestorIds[i];
          if (this.parentDenseVisible(id, ancestor)) {
            reader.set(KnownTagCodec.nameOf(id), ancestorValues[i]);
            consumer.accept(reader);
          }
        }
      }
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          Entry parentEntry = (Entry) parentBucket;
          if (parentEntryVisible(parentEntry, ancestor)) consumer.accept(parentEntry);
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor))
                consumer.accept(parentEntry);
            }
          }
        }
      }
    }
  }

  public <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    if (this.knownCount > 0) {
      EntryReadingHelper reader = new EntryReadingHelper();
      for (int i = 0; i < this.knownCount; ++i) {
        reader.set(KnownTagCodec.nameOf(this.knownIds[i]), this.knownValues[i]);
        consumer.accept(thisObj, reader);
      }
    }

    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        consumer.accept(thisObj, thisEntry);
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.forEachInChain(thisObj, consumer);
      }
    }

    // read-through: parent entries not shadowed locally or tombstoned (kept out of line).
    if (this.parent != null) {
      this.forEachParent(thisObj, consumer);
    }
  }

  private <T> void forEachParent(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    EntryReadingHelper reader = null;
    for (TagMap ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      int ancestorKnownCount = ancestor.knownCount;
      if (ancestorKnownCount > 0) {
        long[] ancestorIds = ancestor.knownIds;
        Object[] ancestorValues = ancestor.knownValues;
        if (reader == null) reader = new EntryReadingHelper();
        for (int i = 0; i < ancestorKnownCount; ++i) {
          long id = ancestorIds[i];
          if (this.parentDenseVisible(id, ancestor)) {
            reader.set(KnownTagCodec.nameOf(id), ancestorValues[i]);
            consumer.accept(thisObj, reader);
          }
        }
      }
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          Entry parentEntry = (Entry) parentBucket;
          if (parentEntryVisible(parentEntry, ancestor)) consumer.accept(thisObj, parentEntry);
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor)) {
                consumer.accept(thisObj, parentEntry);
              }
            }
          }
        }
      }
    }
  }

  public <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
    if (this.knownCount > 0) {
      EntryReadingHelper reader = new EntryReadingHelper();
      for (int i = 0; i < this.knownCount; ++i) {
        reader.set(KnownTagCodec.nameOf(this.knownIds[i]), this.knownValues[i]);
        consumer.accept(thisObj, otherObj, reader);
      }
    }

    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;

        consumer.accept(thisObj, otherObj, thisEntry);
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        thisGroup.forEachInChain(thisObj, otherObj, consumer);
      }
    }

    // read-through: parent entries not shadowed locally or tombstoned (kept out of line).
    if (this.parent != null) {
      this.forEachParent(thisObj, otherObj, consumer);
    }
  }

  private <T, U> void forEachParent(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
    EntryReadingHelper reader = null;
    for (TagMap ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
      int ancestorKnownCount = ancestor.knownCount;
      if (ancestorKnownCount > 0) {
        long[] ancestorIds = ancestor.knownIds;
        Object[] ancestorValues = ancestor.knownValues;
        if (reader == null) reader = new EntryReadingHelper();
        for (int i = 0; i < ancestorKnownCount; ++i) {
          long id = ancestorIds[i];
          if (this.parentDenseVisible(id, ancestor)) {
            reader.set(KnownTagCodec.nameOf(id), ancestorValues[i]);
            consumer.accept(thisObj, otherObj, reader);
          }
        }
      }
      Object[] parentBuckets = ancestor.buckets;
      for (int i = 0; i < parentBuckets.length; ++i) {
        Object parentBucket = parentBuckets[i];
        if (parentBucket instanceof Entry) {
          Entry parentEntry = (Entry) parentBucket;
          if (parentEntryVisible(parentEntry, ancestor))
            consumer.accept(thisObj, otherObj, parentEntry);
        } else if (parentBucket instanceof BucketGroup) {
          for (BucketGroup curGroup = (BucketGroup) parentBucket;
              curGroup != null;
              curGroup = curGroup.prev) {
            for (int j = 0; j < BucketGroup.LEN; ++j) {
              Entry parentEntry = curGroup._entryAt(j);
              if (parentEntry != null && parentEntryVisible(parentEntry, ancestor)) {
                consumer.accept(thisObj, otherObj, parentEntry);
              }
            }
          }
        }
      }
    }
  }

  public void clear() {
    this.checkWriteAccess();

    // Drop the private bucket array back to the shared empty sentinel (also avoids mutating it).
    this.buckets = EMPTY_BUCKETS;
    this.size = 0;
    // clear() removes ALL mappings, including any inherited through read-through. Detaching the
    // parent (rather than tombstoning every inherited key) is simpler and cheaper, and leaves an
    // empty, parent-less map. Detach is one-way -- the parent is never re-pointed.
    this.parent = null;
    this.removedFromParent = null;
    this.knownIds = null;
    this.knownValues = null;
    this.knownCount = 0;
    this.knownGroupMask = 0L;
    this.knownFieldMask = 0L;
  }

  public TagMap freeze() {
    this.frozen = true;

    return this;
  }

  public boolean isFrozen() {
    return this.frozen;
  }

  public void checkWriteAccess() {
    if (this.frozen) throw new IllegalStateException("TagMap frozen");
  }

  void checkIntegrity() {
    // Decided to use if ( cond ) throw new IllegalStateException rather than assert
    // That was done to avoid the extra static initialization needed for an assertion
    // While that's probably an unnecessary optimization, this method is only called in tests

    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object thisBucket = thisBuckets[i];

      if (thisBucket instanceof Entry) {
        Entry thisEntry = (Entry) thisBucket;
        int thisHash = thisEntry.hash();

        int expectedBucket = thisHash & (thisBuckets.length - 1);
        if (expectedBucket != i) {
          throw new IllegalStateException("incorrect bucket");
        }
      } else if (thisBucket instanceof BucketGroup) {
        BucketGroup thisGroup = (BucketGroup) thisBucket;

        for (BucketGroup curGroup = thisGroup; curGroup != null; curGroup = curGroup.prev) {
          for (int j = 0; j < BucketGroup.LEN; ++j) {
            Entry thisEntry = curGroup._entryAt(i);
            if (thisEntry == null) continue;

            int thisHash = thisEntry.hash();
            assert curGroup._hashAt(i) == thisHash;

            int expectedBucket = thisHash & (thisBuckets.length - 1);
            if (expectedBucket != i) {
              throw new IllegalStateException("incorrect bucket");
            }
          }
        }
      }
    }

    // dense store: ids must be unique (no tag stored twice) and the count within array bounds.
    if (this.knownCount > 0) {
      if (this.knownIds == null || this.knownCount > this.knownIds.length) {
        throw new IllegalStateException("incorrect known count");
      }
      for (int i = 0; i < this.knownCount; ++i) {
        for (int j = i + 1; j < this.knownCount; ++j) {
          if (this.knownIds[i] == this.knownIds[j]) {
            throw new IllegalStateException("duplicate known id");
          }
        }
      }
    }

    if (this.size != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
    // Local-structure invariant: the size counter's emptiness must match the local buckets. Uses
    // the local (this.size == 0), NOT isEmpty(), which under read-through resolves the parent too.
    if ((this.size == 0) != this.checkIfEmpty()) {
      throw new IllegalStateException("incorrect empty status");
    }
  }

  int computeSize() {
    Object[] thisBuckets = this.buckets;

    int size = 0;
    for (int i = 0; i < thisBuckets.length; ++i) {
      Object curBucket = thisBuckets[i];

      if (curBucket instanceof Entry) {
        size += 1;
      } else if (curBucket instanceof BucketGroup) {
        BucketGroup curGroup = (BucketGroup) curBucket;
        size += curGroup.sizeInChain();
      }
    }
    return size;
  }

  boolean checkIfEmpty() {
    Object[] thisBuckets = this.buckets;

    for (int i = 0; i < thisBuckets.length; ++i) {
      Object curBucket = thisBuckets[i];

      if (curBucket instanceof Entry) {
        return false;
      } else if (curBucket instanceof BucketGroup) {
        BucketGroup curGroup = (BucketGroup) curBucket;
        if (!curGroup.isEmptyChain()) return false;
      }
    }

    return true;
  }

  @Override
  public Object compute(
      String key, BiFunction<? super String, ? super Object, ? extends Object> remappingFunction) {
    this.checkWriteAccess();

    return Map.super.compute(key, remappingFunction);
  }

  @Override
  public Object computeIfAbsent(
      String key, Function<? super String, ? extends Object> mappingFunction) {
    this.checkWriteAccess();

    return Map.super.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Object computeIfPresent(
      String key, BiFunction<? super String, ? super Object, ? extends Object> remappingFunction) {
    this.checkWriteAccess();

    return Map.super.computeIfPresent(key, remappingFunction);
  }

  @Override
  public String toString() {
    return toPrettyString();
  }

  /**
   * Standard toString implementation - output is similar to {@link java.util.HashMap#toString()}
   */
  String toPrettyString() {
    boolean first = true;

    StringBuilder ledger = new StringBuilder(128);
    ledger.append('{');
    for (EntryReader entry : this) {
      if (first) {
        first = false;
      } else {
        ledger.append(", ");
      }

      ledger.append(entry.tag()).append('=').append(entry.stringValue());
    }
    ledger.append('}');
    return ledger.toString();
  }

  /**
   * toString that more visibility into the internal structure of TagMap - primarily for deep
   * debugging
   */
  String toInternalString() {
    Object[] thisBuckets = this.buckets;

    StringBuilder ledger = new StringBuilder(128);
    for (int i = 0; i < thisBuckets.length; ++i) {
      ledger.append('[').append(i).append("] = ");

      Object thisBucket = thisBuckets[i];
      if (thisBucket == null) {
        ledger.append("null");
      } else if (thisBucket instanceof Entry) {
        ledger.append('{').append(thisBucket).append('}');
      } else if (thisBucket instanceof BucketGroup) {
        for (BucketGroup curGroup = (BucketGroup) thisBucket;
            curGroup != null;
            curGroup = curGroup.prev) {
          ledger.append(curGroup).append(" -> ");
        }
      }
      ledger.append('\n');
    }
    return ledger.toString();
  }

  abstract static class IteratorBase {
    private final TagMap map;

    // the level whose buckets are currently being walked: the leaf (map) first, then each ancestor
    // in turn (read-through union). map == level means we're on the leaf's own entries.
    private TagMap level;
    private Object[] buckets;

    // Currency is EntryReader, not Entry: a BUCKET entry is its own (real, retain-safe) Entry, but
    // a
    // DENSE entry is emitted via the reused denseReader flyweight (alloc-free, "use now"). This is
    // the contract of TagMap.iterator()/keySet()/values(). entrySet() (Iterator<Map.Entry>) sits on
    // top and calls .entry() per next() to get a real retain-safe Entry (see EntriesIterator).
    private EntryReader nextEntry;
    private EntryReadingHelper denseReader; // lazily created on the first dense emit

    private int bucketIndex = -1;

    private BucketGroup group = null;
    private int groupIndex = 0;

    // dense-store cursor for the current level's known tags; advance() resets it when it moves to
    // the next ancestor level (read-through union).
    private int knownIndex = 0;

    IteratorBase(TagMap map) {
      this.map = map;
      this.level = map;
      this.buckets = map.buckets;
    }

    public final boolean hasNext() {
      if (this.nextEntry != null) return true;

      this.nextEntry = this.advance();
      return this.nextEntry != null;
    }

    final EntryReader nextEntryOrThrowNoSuchElement() {
      if (this.nextEntry != null) {
        EntryReader nextEntry = this.nextEntry;
        this.nextEntry = null;
        return nextEntry;
      }

      if (this.hasNext()) {
        return this.nextEntry;
      } else {
        throw new NoSuchElementException();
      }
    }

    final EntryReader nextEntryOrNull() {
      if (this.nextEntry != null) {
        EntryReader nextEntry = this.nextEntry;
        this.nextEntry = null;
        return nextEntry;
      }

      return this.hasNext() ? this.nextEntry : null;
    }

    private final EntryReader advance() {
      while (true) {
        // phase 1: drain the current level's dense known tags before its buckets. Leaf dense always
        // emits; ancestor dense only if visible from the leaf (not shadowed by a nearer dense entry
        // and not tombstoned). Emitted via the reused denseReader flyweight -- NO per-entry Entry
        // alloc (the read/serialize alloc win).
        if (this.knownIndex < this.level.knownCount) {
          int i = this.knownIndex++;
          long id = this.level.knownIds[i];
          if (this.level == this.map || this.map.parentDenseVisible(id, this.level)) {
            return this.emitDense(id, this.level.knownValues[i]);
          }
          continue; // ancestor dense entry shadowed/tombstoned -> skip
        }

        // phase 2: the current level's buckets.
        Entry tagEntry = this.rawAdvance();
        if (tagEntry != null) {
          // leaf entries emit as-is; ancestor entries only if visible from the leaf -- not shadowed
          // by a nearer level and not tombstoned. (parentEntryVisible walks the nearer levels.)
          if (this.level == this.map || this.map.parentEntryVisible(tagEntry, this.level)) {
            return tagEntry;
          }
          continue; // ancestor entry shadowed/tombstoned -> skip
        }

        // current level exhausted; advance to the next ancestor (read-through union), resetting
        // both
        // the per-level dense cursor and the bucket cursor for the new level.
        if (this.level.parent != null) {
          this.level = this.level.parent;
          this.knownIndex = 0;
          this.buckets = this.level.buckets;
          this.bucketIndex = -1;
          this.group = null;
          this.groupIndex = 0;
          continue;
        }
        return null;
      }
    }

    /** Sets and returns the reused dense flyweight (lazily created); "use now", do not retain. */
    private EntryReader emitDense(long tagId, Object value) {
      EntryReadingHelper reader = this.denseReader;
      if (reader == null) {
        reader = this.denseReader = new EntryReadingHelper();
      }
      reader.set(KnownTagCodec.nameOf(tagId), value);
      return reader;
    }

    /** Next raw entry in the current bucket array, ignoring shadowing/tombstones. */
    private final Entry rawAdvance() {
      while (this.bucketIndex < this.buckets.length) {
        if (this.group != null) {
          for (++this.groupIndex; this.groupIndex < BucketGroup.LEN; ++this.groupIndex) {
            Entry tagEntry = this.group._entryAt(this.groupIndex);
            if (tagEntry != null) return tagEntry;
          }

          // done processing - that group, go to next group
          this.group = this.group.prev;
          this.groupIndex = -1;
        }

        // if the group is null, then we've finished the current bucket - so advance the bucket
        if (this.group == null) {
          for (++this.bucketIndex; this.bucketIndex < this.buckets.length; ++this.bucketIndex) {
            Object bucket = this.buckets[this.bucketIndex];

            if (bucket instanceof Entry) {
              return (Entry) bucket;
            } else if (bucket instanceof BucketGroup) {
              this.group = (BucketGroup) bucket;
              this.groupIndex = -1;

              break;
            }
          }
        }
      }

      return null;
    }
  }

  static final class EntryReaderIterator extends IteratorBase implements Iterator<EntryReader> {
    EntryReaderIterator(TagMap map) {
      super(map);
    }

    @Override
    public EntryReader next() {
      return this.nextEntryOrThrowNoSuchElement();
    }
  }

  /**
   * BucketGroup is a compromise for performance over a linked list or array
   *
   * <ul>
   *   <li>linked list - would prevent TagEntry-s from being immutable and would limit sharing
   *       opportunities
   *   <li>arrays - wouldn't be able to store hashes close together
   *   <li>parallel arrays (one for hashes & another for entries) would require more allocation
   * </ul>
   */
  static final class BucketGroup {
    static final int LEN = 4;

    /*
     * To make search operations on BucketGroups fast, the hashes for each entry are held inside
     * the BucketGroup.  This avoids pointer chasing to inspect each Entry object.
     * <p>
     * As a further optimization, the hashes are deliberately placed next to each other.
     * The intention is that the hashes will all end up in the same cache line, so loading
     * one hash effectively loads the others for free.
     * <p>
     * A hash of zero indicates an available slot, the hashes passed to BucketGroup must be "adjusted"
     * hashes which can never be zero.  The zero handling is done by TagMap#_hash.
     */
    int hash0 = 0;
    int hash1 = 0;
    int hash2 = 0;
    int hash3 = 0;

    Entry entry0 = null;
    Entry entry1 = null;
    Entry entry2 = null;
    Entry entry3 = null;

    BucketGroup prev = null;

    BucketGroup() {}

    /** New group with an entry pointing to existing BucketGroup */
    BucketGroup(int hash0, Entry entry0, BucketGroup prev) {
      this.hash0 = hash0;
      this.entry0 = entry0;

      this.prev = prev;
    }

    /** New group composed of two entries */
    BucketGroup(int hash0, Entry entry0, int hash1, Entry entry1) {
      this.hash0 = hash0;
      this.entry0 = entry0;

      this.hash1 = hash1;
      this.entry1 = entry1;
    }

    /** New group composed of 4 entries - used for cloning */
    BucketGroup(
        int hash0,
        Entry entry0,
        int hash1,
        Entry entry1,
        int hash2,
        Entry entry2,
        int hash3,
        Entry entry3) {
      this.hash0 = hash0;
      this.entry0 = entry0;

      this.hash1 = hash1;
      this.entry1 = entry1;

      this.hash2 = hash2;
      this.entry2 = entry2;

      this.hash3 = hash3;
      this.entry3 = entry3;
    }

    Entry _entryAt(int index) {
      switch (index) {
        case 0:
          return this.entry0;

        case 1:
          return this.entry1;

        case 2:
          return this.entry2;

        case 3:
          return this.entry3;

          // Do not use default case, that creates a 5% cost on entry handling
      }

      return null;
    }

    int _hashAt(int index) {
      switch (index) {
        case 0:
          return this.hash0;

        case 1:
          return this.hash1;

        case 2:
          return this.hash2;

        case 3:
          return this.hash3;

          // Do not use default case, that creates a 5% cost on entry handling
      }

      return 0;
    }

    int sizeInChain() {
      int size = 0;
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        size += curGroup._size();
      }
      return size;
    }

    int _size() {
      return (this.hash0 == 0 ? 0 : 1)
          + (this.hash1 == 0 ? 0 : 1)
          + (this.hash2 == 0 ? 0 : 1)
          + (this.hash3 == 0 ? 0 : 1);
    }

    boolean isEmptyChain() {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        if (!curGroup._isEmpty()) return false;
      }
      return true;
    }

    boolean _isEmpty() {
      return (this.hash0 | this.hash1 | this.hash2 | this.hash3) == 0;
    }

    BucketGroup findContainingGroupInChain(int hash, String tag) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        if (curGroup._find(hash, tag) != null) return curGroup;
      }
      return null;
    }

    Entry findInChain(int hash, String tag) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        Entry curEntry = curGroup._find(hash, tag);
        if (curEntry != null) return curEntry;
      }
      return null;
    }

    Entry _find(int hash, String tag) {
      // if ( this._mayContain(hash) ) return null;

      if (this.hash0 == hash && this.entry0.matches(tag)) {
        return this.entry0;
      } else if (this.hash1 == hash && this.entry1.matches(tag)) {
        return this.entry1;
      } else if (this.hash2 == hash && this.entry2.matches(tag)) {
        return this.entry2;
      } else if (this.hash3 == hash && this.entry3.matches(tag)) {
        return this.entry3;
      }
      return null;
    }

    BucketGroup replaceOrInsertAllInChain(BucketGroup thatHeadGroup) {
      BucketGroup thisOrigHeadGroup = this;
      BucketGroup thisNewestHeadGroup = thisOrigHeadGroup;

      for (BucketGroup thatCurGroup = thatHeadGroup;
          thatCurGroup != null;
          thatCurGroup = thatCurGroup.prev) {
        // First phase - tries to replace or insert each entry in the existing bucket chain
        // Only need to search the original groups for replacements
        // The whole chain is eligible for insertions
        boolean handled0 =
            (thatCurGroup.hash0 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash0, thatCurGroup.entry0)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash0, thatCurGroup.entry0);

        boolean handled1 =
            (thatCurGroup.hash1 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash1, thatCurGroup.entry1)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash1, thatCurGroup.entry1);

        boolean handled2 =
            (thatCurGroup.hash2 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash2, thatCurGroup.entry2)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash2, thatCurGroup.entry2);

        boolean handled3 =
            (thatCurGroup.hash3 == 0)
                || (thisOrigHeadGroup.replaceInChain(thatCurGroup.hash3, thatCurGroup.entry3)
                    != null)
                || thisNewestHeadGroup.insertInChain(thatCurGroup.hash3, thatCurGroup.entry3);

        // Second phase - takes any entries that weren't handled by phase 1 and puts them
        // into a new BucketGroup.  Since BucketGroups are fixed size, we know that the
        // left over entries from one BucketGroup will fit in the new BucketGroup.
        if (!handled0 || !handled1 || !handled2 || !handled3) {
          // Rather than calling insert one time per entry
          // Exploiting the fact that the new group is known to be empty
          // And that BucketGroups are allowed to have holes in them (to allow for removal),
          // so each unhandled entry from the source group is simply placed in
          // the same slot in the new group
          BucketGroup thisNewHashGroup = new BucketGroup();
          if (!handled0) {
            thisNewHashGroup.hash0 = thatCurGroup.hash0;
            thisNewHashGroup.entry0 = thatCurGroup.entry0;
          }
          if (!handled1) {
            thisNewHashGroup.hash1 = thatCurGroup.hash1;
            thisNewHashGroup.entry1 = thatCurGroup.entry1;
          }
          if (!handled2) {
            thisNewHashGroup.hash2 = thatCurGroup.hash2;
            thisNewHashGroup.entry2 = thatCurGroup.entry2;
          }
          if (!handled3) {
            thisNewHashGroup.hash3 = thatCurGroup.hash3;
            thisNewHashGroup.entry3 = thatCurGroup.entry3;
          }
          thisNewHashGroup.prev = thisNewestHeadGroup;

          thisNewestHeadGroup = thisNewHashGroup;
        }
      }

      return thisNewestHeadGroup;
    }

    Entry replaceInChain(int hash, Entry entry) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        Entry prevEntry = curGroup._replace(hash, entry);
        if (prevEntry != null) return prevEntry;
      }
      return null;
    }

    Entry _replace(int hash, Entry entry) {
      // if ( this._mayContain(hash) ) return null;

      // first check to see if the item is already present
      Entry prevEntry = null;
      if (this.hash0 == hash && this.entry0.matches(entry.tag)) {
        prevEntry = this.entry0;
        this.entry0 = entry;
      } else if (this.hash1 == hash && this.entry1.matches(entry.tag)) {
        prevEntry = this.entry1;
        this.entry1 = entry;
      } else if (this.hash2 == hash && this.entry2.matches(entry.tag)) {
        prevEntry = this.entry2;
        this.entry2 = entry;
      } else if (this.hash3 == hash && this.entry3.matches(entry.tag)) {
        prevEntry = this.entry3;
        this.entry3 = entry;
      }

      return prevEntry;
    }

    boolean insertInChain(int hash, Entry entry) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        if (curGroup._insert(hash, entry)) return true;
      }
      return false;
    }

    boolean _insert(int hash, Entry entry) {
      boolean inserted = false;
      if (this.hash0 == 0) {
        this.hash0 = hash;
        this.entry0 = entry;

        inserted = true;
      } else if (this.hash1 == 0) {
        this.hash1 = hash;
        this.entry1 = entry;

        inserted = true;
      } else if (this.hash2 == 0) {
        this.hash2 = hash;
        this.entry2 = entry;

        inserted = true;
      } else if (this.hash3 == 0) {
        this.hash3 = hash;
        this.entry3 = entry;

        inserted = true;
      }
      return inserted;
    }

    BucketGroup removeGroupInChain(BucketGroup removeGroup) {
      BucketGroup firstGroup = this;
      if (firstGroup == removeGroup) {
        return firstGroup.prev;
      }

      for (BucketGroup priorGroup = firstGroup, curGroup = priorGroup.prev;
          curGroup != null;
          priorGroup = curGroup, curGroup = priorGroup.prev) {
        if (curGroup == removeGroup) {
          priorGroup.prev = curGroup.prev;
        }
      }
      return firstGroup;
    }

    Entry _remove(int hash, String tag) {
      Entry existingEntry = null;
      if (this.hash0 == hash && this.entry0.matches(tag)) {
        existingEntry = this.entry0;

        this.hash0 = 0;
        this.entry0 = null;
      } else if (this.hash1 == hash && this.entry1.matches(tag)) {
        existingEntry = this.entry1;

        this.hash1 = 0;
        this.entry1 = null;
      } else if (this.hash2 == hash && this.entry2.matches(tag)) {
        existingEntry = this.entry2;

        this.hash2 = 0;
        this.entry2 = null;
      } else if (this.hash3 == hash && this.entry3.matches(tag)) {
        existingEntry = this.entry3;

        this.hash3 = 0;
        this.entry3 = null;
      }
      return existingEntry;
    }

    void forEachInChain(Consumer<? super EntryReader> consumer) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._forEach(consumer);
      }
    }

    void _forEach(Consumer<? super EntryReader> consumer) {
      if (this.entry0 != null) consumer.accept(this.entry0);
      if (this.entry1 != null) consumer.accept(this.entry1);
      if (this.entry2 != null) consumer.accept(this.entry2);
      if (this.entry3 != null) consumer.accept(this.entry3);
    }

    <T> void forEachInChain(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._forEach(thisObj, consumer);
      }
    }

    <T> void _forEach(T thisObj, BiConsumer<T, ? super EntryReader> consumer) {
      if (this.entry0 != null) consumer.accept(thisObj, this.entry0);
      if (this.entry1 != null) consumer.accept(thisObj, this.entry1);
      if (this.entry2 != null) consumer.accept(thisObj, this.entry2);
      if (this.entry3 != null) consumer.accept(thisObj, this.entry3);
    }

    <T, U> void forEachInChain(
        T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._forEach(thisObj, otherObj, consumer);
      }
    }

    <T, U> void _forEach(T thisObj, U otherObj, TriConsumer<T, U, ? super EntryReader> consumer) {
      if (this.entry0 != null) consumer.accept(thisObj, otherObj, this.entry0);
      if (this.entry1 != null) consumer.accept(thisObj, otherObj, this.entry1);
      if (this.entry2 != null) consumer.accept(thisObj, otherObj, this.entry2);
      if (this.entry3 != null) consumer.accept(thisObj, otherObj, this.entry3);
    }

    void fillMapFromChain(Map<? super String, ? super Object> map) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._fillMap(map);
      }
    }

    void _fillMap(Map<? super String, ? super Object> map) {
      Entry entry0 = this.entry0;
      if (entry0 != null) map.put(entry0.tag, entry0.objectValue());

      Entry entry1 = this.entry1;
      if (entry1 != null) map.put(entry1.tag, entry1.objectValue());

      Entry entry2 = this.entry2;
      if (entry2 != null) map.put(entry2.tag, entry2.objectValue());

      Entry entry3 = this.entry3;
      if (entry3 != null) map.put(entry3.tag, entry3.objectValue());
    }

    void fillStringMapFromChain(Map<? super String, ? super String> map) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        curGroup._fillStringMap(map);
      }
    }

    void _fillStringMap(Map<? super String, ? super String> map) {
      Entry entry0 = this.entry0;
      if (entry0 != null) map.put(entry0.tag, entry0.stringValue());

      Entry entry1 = this.entry1;
      if (entry1 != null) map.put(entry1.tag, entry1.stringValue());

      Entry entry2 = this.entry2;
      if (entry2 != null) map.put(entry2.tag, entry2.stringValue());

      Entry entry3 = this.entry3;
      if (entry3 != null) map.put(entry3.tag, entry3.stringValue());
    }

    BucketGroup cloneChain() {
      BucketGroup thisClone = this._cloneEntries();

      BucketGroup thisPriorClone = thisClone;
      for (BucketGroup curGroup = this.prev; curGroup != null; curGroup = curGroup.prev) {
        BucketGroup newClone = curGroup._cloneEntries();
        thisPriorClone.prev = newClone;

        thisPriorClone = newClone;
      }

      return thisClone;
    }

    BucketGroup _cloneEntries() {
      return new BucketGroup(
          this.hash0, this.entry0,
          this.hash1, this.entry1,
          this.hash2, this.entry2,
          this.hash3, this.entry3);
    }

    @Override
    public String toString() {
      StringBuilder ledger = new StringBuilder(32);
      ledger.append('[');
      for (int i = 0; i < BucketGroup.LEN; ++i) {
        if (i != 0) ledger.append(", ");

        ledger.append(this._entryAt(i));
      }
      ledger.append(']');
      return ledger.toString();
    }
  }

  static final class Entries extends AbstractSet<Map.Entry<String, Object>> {
    private final TagMap map;

    Entries(TagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.size();
    }

    @Override
    public boolean isEmpty() {
      return this.map.isEmpty();
    }

    @Override
    public Iterator<Map.Entry<String, Object>> iterator() {
      return new EntriesIterator(this.map);
    }
  }

  /**
   * entrySet() yields real, retain-safe {@code Map.Entry} objects. It sits on top of the
   * EntryReader iterator and materializes each via {@code .entry()}: a bucket entry's reader IS the
   * real stored Entry (returns {@code this}, free); a dense entry's flyweight materializes a fresh
   * Entry. Deliberately NOT alloc-optimized for dense — bulk reads use {@code forEach}/EntryReader,
   * and manual instrumentation does point get/set, not bulk entrySet iteration.
   */
  static final class EntriesIterator extends IteratorBase
      implements Iterator<Map.Entry<String, Object>> {
    EntriesIterator(TagMap map) {
      super(map);
    }

    @Override
    public Map.Entry<String, Object> next() {
      return this.nextEntryOrThrowNoSuchElement().entry();
    }
  }

  static final class Keys extends AbstractSet<String> {
    final TagMap map;

    Keys(TagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.size();
    }

    @Override
    public boolean isEmpty() {
      return this.map.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
      return this.map.containsKey(o);
    }

    @Override
    public Iterator<String> iterator() {
      return new KeysIterator(this.map);
    }
  }

  static final class KeysIterator extends IteratorBase implements Iterator<String> {
    KeysIterator(TagMap map) {
      super(map);
    }

    @Override
    public String next() {
      return this.nextEntryOrThrowNoSuchElement().tag();
    }
  }

  static final class Values extends AbstractCollection<Object> {
    final TagMap map;

    Values(TagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.size();
    }

    @Override
    public boolean isEmpty() {
      return this.map.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
      return this.map.containsValue(o);
    }

    @Override
    public Iterator<Object> iterator() {
      return new ValuesIterator(this.map);
    }
  }

  static final class ValuesIterator extends IteratorBase implements Iterator<Object> {
    ValuesIterator(TagMap map) {
      super(map);
    }

    @Override
    public Object next() {
      return this.nextEntryOrThrowNoSuchElement().objectValue();
    }
  }
}

final class EntryReadingHelper implements TagMap.EntryReader {
  private Map.Entry<String, Object> mapEntry;
  private String tag;
  private Object value;

  void set(String tag, Object value) {
    this.mapEntry = null;
    this.tag = tag;
    this.value = value;
  }

  void set(Map.Entry<String, Object> mapEntry) {
    this.mapEntry = mapEntry;
    this.tag = mapEntry.getKey();
    this.value = mapEntry.getValue();
  }

  @Override
  public String tag() {
    return this.tag;
  }

  @Override
  public byte type() {
    return TagValueConversions.typeOf(this.value);
  }

  @Override
  public boolean is(byte type) {
    return TagValueConversions.isA(this.value, type);
  }

  @Override
  public boolean isNumber() {
    return TagValueConversions.isNumber(this.value);
  }

  @Override
  public boolean isNumericPrimitive() {
    return TagValueConversions.isNumericPrimitive(this.value);
  }

  @Override
  public boolean isObject() {
    return TagValueConversions.isObject(this.value);
  }

  @Override
  public boolean booleanValue() {
    return TagValueConversions.toBoolean(this.value);
  }

  @Override
  public int intValue() {
    return TagValueConversions.toInt(this.value);
  }

  @Override
  public long longValue() {
    return TagValueConversions.toLong(this.value);
  }

  @Override
  public float floatValue() {
    return TagValueConversions.toFloat(this.value);
  }

  @Override
  public double doubleValue() {
    return TagValueConversions.toDouble(this.value);
  }

  @Override
  public String stringValue() {
    return TagValueConversions.toString(this.value);
  }

  @Override
  public Object objectValue() {
    return this.value;
  }

  @Override
  public TagMap.Entry entry() {
    return TagMap.Entry.newAnyEntry(this.tag, this.value);
  }

  @Override
  public Map.Entry<String, Object> mapEntry() {
    Map.Entry<String, Object> mapEntry = this.mapEntry;
    return (mapEntry != null) ? mapEntry : this.entry();
  }
}

final class TagValueConversions {
  TagValueConversions() {}

  static byte typeOf(Object value) {
    if (value instanceof Integer) {
      return TagMap.EntryReader.INT;
    } else if (value instanceof Long) {
      return TagMap.EntryReader.LONG;
    } else if (value instanceof Double) {
      return TagMap.EntryReader.DOUBLE;
    } else if (value instanceof Float) {
      return TagMap.EntryReader.FLOAT;
    } else if (value instanceof Boolean) {
      return TagMap.EntryReader.BOOLEAN;
    } else if (value instanceof Short) {
      return TagMap.EntryReader.INT;
    } else if (value instanceof Byte) {
      return TagMap.EntryReader.INT;
    } else {
      // NOTE: Character is currently deliberately treated as OBJECT
      return TagMap.EntryReader.OBJECT;
    }
  }

  static boolean isA(Object value, byte type) {
    switch (type) {
      case TagMap.EntryReader.BOOLEAN:
        return (value instanceof Boolean);

      case TagMap.EntryReader.INT:
        return (value instanceof Integer) || (value instanceof Short) || (value instanceof Byte);

      case TagMap.EntryReader.LONG:
        return (value instanceof Long);

      case TagMap.EntryReader.FLOAT:
        return (value instanceof Float);

      case TagMap.EntryReader.DOUBLE:
        return (value instanceof Double);

      case TagMap.EntryReader.OBJECT:
        return true;

      default:
        return false;
    }
  }

  static boolean isNumber(Object value) {
    return (value instanceof Number);
  }

  static boolean isNumericPrimitive(Object value) {
    return (value instanceof Integer)
        || (value instanceof Long)
        || (value instanceof Double)
        || (value instanceof Float)
        || (value instanceof Short)
        || (value instanceof Byte);
  }

  static boolean isObject(Object value) {
    boolean isSupportedPrimitive =
        (value instanceof Integer)
            || (value instanceof Long)
            || (value instanceof Double)
            || (value instanceof Float)
            || (value instanceof Boolean)
            || (value instanceof Short)
            || (value instanceof Byte);

    // NOTE: Character is just treated as Object
    return !isSupportedPrimitive;
  }

  static boolean toBooleanOrDefault(Object value, boolean defaultValue) {
    return (value == null) ? defaultValue : toBoolean(value);
  }

  static boolean toBoolean(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    } else if (value instanceof Number) {
      // NOTE: This cannot be intValue() because intValue of larger types is 0 when
      // the actual value would be less than Integer.MIN_VALUE or for floating point
      // types is very close to zero, so using doubleValue instead.

      // While this is a bit ugly, coerced toBoolean is uncommon
      return ((Number) value).doubleValue() != 0D;
    } else {
      return false;
    }
  }

  static int toIntOrDefault(Object value, int defaultValue) {
    return (value == null) ? defaultValue : toInt(value);
  }

  static int toInt(Object value) {
    if (value instanceof Integer) {
      return (Integer) value;
    } else if (value instanceof Number) {
      return ((Number) value).intValue();
    } else if (value instanceof Boolean) {
      return (Boolean) value ? 1 : 0;
    } else {
      return 0;
    }
  }

  static long toLong(Object value) {
    if (value instanceof Long) {
      return (Long) value;
    } else if (value instanceof Number) {
      return ((Number) value).longValue();
    } else if (value instanceof Boolean) {
      return (Boolean) value ? 1L : 0L;
    } else {
      return 0L;
    }
  }

  static long toLongOrDefault(Object value, long defaultValue) {
    return (value == null) ? defaultValue : toLong(value);
  }

  static float toFloat(Object value) {
    if (value instanceof Float) {
      return (Float) value;
    } else if (value instanceof Number) {
      return ((Number) value).floatValue();
    } else if (value instanceof Boolean) {
      return (Boolean) value ? 1F : 0F;
    } else {
      return 0F;
    }
  }

  static float toFloatOrDefault(Object value, float defaultValue) {
    return (value == null) ? defaultValue : toFloat(value);
  }

  static double toDouble(Object value) {
    if (value instanceof Double) {
      return (Double) value;
    } else if (value instanceof Number) {
      return ((Number) value).doubleValue();
    } else if (value instanceof Boolean) {
      return (Boolean) value ? 1D : 0D;
    } else {
      return 0D;
    }
  }

  static double toDoubleOrDefault(Object value, double defaultValue) {
    return (value == null) ? defaultValue : toDouble(value);
  }

  static String toString(Object value) {
    return value.toString();
  }
}
