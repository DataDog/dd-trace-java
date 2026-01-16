package datadog.trace.api;

import datadog.trace.api.function.TriConsumer;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
public interface TagMap extends Map<String, Object>, Iterable<TagMap.EntryReader> {
  /** Immutable empty TagMap - similar to {@link Collections#emptyMap()} */
  TagMap EMPTY = TagMapFactory.INSTANCE.empty();

  /** Creates a new mutable TagMap that contains the contents of <code>map</code> */
  static TagMap fromMap(Map<String, ?> map) {
    TagMap tagMap = TagMap.create(map.size());
    tagMap.putAll(map);
    return tagMap;
  }

  /** Creates a new immutable TagMap that contains the contents of <code>map</code> */
  static TagMap fromMapImmutable(Map<String, ?> map) {
    if (map.isEmpty()) {
      return TagMap.EMPTY;
    } else {
      return fromMap(map).freeze();
    }
  }

  static TagMap create() {
    return TagMapFactory.INSTANCE.create();
  }

  static TagMap create(int size) {
    return TagMapFactory.INSTANCE.create(size);
  }

  /** Creates a new TagMap.Ledger */
  static Ledger ledger() {
    return new Ledger();
  }

  /** Creates a new TagMap.Ledger which handles <code>size</code> modifications before expansion */
  static Ledger ledger(int size) {
    return new Ledger(size);
  }

  boolean isOptimized();

  /** Inefficiently implemented for optimized TagMap */
  @Deprecated
  Set<String> keySet();

  Iterator<String> tagIterator();

  /** Inefficiently implemented for optimized TagMap - requires boxing primitives */
  @Deprecated
  Collection<Object> values();

  Iterator<Object> valueIterator();

  // @Deprecated -- not deprecated until OptimizedTagMap becomes the default
  Set<java.util.Map.Entry<String, Object>> entrySet();

  /**
   * Deprecated in favor of typed getters like...
   *
   * <ul>
   *   <li>{@link TagMap#getObject(String)}
   *   <li>{@link TagMap#getString(String)}
   *   <li>{@link TagMap#getBoolean(String)}
   *   <li>...
   * </ul>
   */
  @Deprecated
  Object get(Object tag);

  /** Provides the corresponding entry value as an Object - boxing if necessary */
  Object getObject(String tag);

  /** Provides the corresponding entry value as a String - calling toString if necessary */
  String getString(String tag);

  boolean getBoolean(String tag);

  boolean getBooleanOrDefault(String tag, boolean defaultValue);

  int getInt(String tag);

  int getIntOrDefault(String tag, int defaultValue);

  long getLong(String tag);

  long getLongOrDefault(String tag, long defaultValue);

  float getFloat(String tag);

  float getFloatOrDefault(String tag, float defaultValue);

  double getDouble(String tag);

  double getDoubleOrDefault(String tag, double defaultValue);

  /**
   * Provides the corresponding Entry object - preferable w/ optimized TagMap if the Entry needs to
   * have its type checked
   */
  Entry getEntry(String tag);

  /**
   * Deprecated in favor of {@link TagMap#set} methods. set methods don't return the prior value and
   * are implemented efficiently for both the legacy and optimized implementations of TagMap.
   */
  @Deprecated
  Object put(String tag, Object value);

  /** Sets value without returning prior value - optimal for legacy & optimized implementations */
  void set(String tag, Object value);

  /**
   * Similar to {@link TagMap#set(String, Object)} but more efficient when working with
   * CharSequences and Strings. Depending on this situation, this methods avoids having to do type
   * resolution later on
   */
  void set(String tag, CharSequence value);

  void set(String tag, boolean value);

  void set(String tag, int value);

  void set(String tag, long value);

  void set(String tag, float value);

  void set(String tag, double value);

  void set(EntryReader newEntry);

  /** sets the value while returning the prior Entry */
  Entry getAndSet(String tag, Object value);

  Entry getAndSet(String tag, CharSequence value);

  Entry getAndSet(String tag, boolean value);

  Entry getAndSet(String tag, int value);

  Entry getAndSet(String tag, long value);

  Entry getAndSet(String tag, float value);

  Entry getAndSet(String tag, double value);

  /**
   * TagMap specific method that places an Entry directly into an optimized TagMap avoiding need to
   * allocate a new Entry object
   */
  Entry getAndSet(Entry newEntry);

  void putAll(Map<? extends String, ? extends Object> map);

  /**
   * Similar to {@link Map#putAll(Map)} but optimized to quickly copy from one TagMap to another
   *
   * <p>For optimized TagMaps, this method takes advantage of the consistent TagMap layout to
   * quickly handle each bucket. And similar to {@link TagMap#(Entry)} this method shares Entry
   * objects from the source TagMap
   */
  void putAll(TagMap that);

  void fillMap(Map<? super String, Object> map);

  void fillStringMap(Map<? super String, ? super String> stringMap);

  /**
   * Deprecated in favor of {@link TagMap#remove(String)} which returns a boolean and is efficiently
   * implemented for both legacy and optimal TagMaps
   */
  @Deprecated
  Object remove(Object tag);

  /**
   * Similar to {@link Map#remove(Object)} but doesn't return the prior value (orEntry). Preferred
   * when prior value isn't needed - best for both legacy and optimal TagMaps
   */
  boolean remove(String tag);

  /**
   * Similar to {@link Map#remove(Object)} but returns the prior Entry object rather than the prior
   * value. For optimized TagMap-s, this method is preferred because it avoids additional boxing.
   */
  Entry getAndRemove(String tag);

  /** Returns a mutable copy of this TagMap */
  TagMap copy();

  /**
   * Returns an immutable copy of this TagMap This method is more efficient than <code>
   * map.copy().freeze()</code> when called on an immutable TagMap
   */
  TagMap immutableCopy();

  /**
   * Provides an Iterator over the Entry-s of the TagMap Equivalent to <code>entrySet().iterator()
   * </code>, but with less allocation
   */
  @Override
  Iterator<EntryReader> iterator();

  Stream<EntryReader> stream();

  /**
   * Visits each Entry in this TagMap This method is more efficient than {@link TagMap#iterator()}
   */
  void forEach(Consumer<? super TagMap.EntryReader> consumer);

  /**
   * Version of forEach that takes an extra context object that is passed as the first argument to
   * the consumer
   *
   * <p>The intention is to use this method to avoid using a capturing lambda
   */
  <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer);

  /**
   * Version of forEach that takes two extra context objects that are passed as the first two
   * argument to the consumer
   *
   * <p>The intention is to use this method to avoid using a capturing lambda
   */
  <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer);

  /** Clears the TagMap */
  void clear();

  /** Freeze the TagMap preventing further modification - returns <code>this</code> TagMap */
  TagMap freeze();

  /** Indicates if this map is frozen */
  boolean isFrozen();

  /** Checks if the TagMap is writable - if not throws {@link IllegalStateException} */
  void checkWriteAccess();

  abstract class EntryChange {
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

  final class EntryRemoval extends EntryChange {
    EntryRemoval(String tag) {
      super(tag);
    }

    @Override
    public boolean isRemoval() {
      return true;
    }
  }

  interface EntryReader {
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

  final class Entry extends EntryChange implements Map.Entry<String, Object>, EntryReader {
    /*
     * Special value used for Objects that haven't been type checked yet.
     * These objects might be primitive box objects.
     */
    static final byte ANY = 0;

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
  final class Ledger implements Iterable<EntryChange> {
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
     * @return
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

    TagMap build(TagMapFactory<?> mapFactory) {
      TagMap map = mapFactory.create(this.estimateSize());
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

    TagMap buildImmutable(TagMapFactory<?> mapFactory) {
      if (this.nextPos == 0) {
        return mapFactory.empty();
      } else {
        return this.build(mapFactory).freeze();
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
}

/*
 * Using a class, so class hierarchy analysis kicks in
 * That will allow all of the calls to create methods to be devirtualized without a guard
 */
abstract class TagMapFactory<MapT extends TagMap> {
  public static final TagMapFactory<?> INSTANCE =
      createFactory(Config.get().isOptimizedMapEnabled());

  static final TagMapFactory<?> createFactory(boolean useOptimized) {
    return useOptimized ? OptimizedTagMapFactory.INSTANCE : LegacyTagMapFactory.INSTANCE;
  }

  public abstract MapT create();

  public abstract MapT create(int size);

  public abstract MapT empty();
}

final class OptimizedTagMapFactory extends TagMapFactory<OptimizedTagMap> {
  static final OptimizedTagMapFactory INSTANCE = new OptimizedTagMapFactory();

  private OptimizedTagMapFactory() {}

  @Override
  public OptimizedTagMap create() {
    return new OptimizedTagMap();
  }

  @Override
  public OptimizedTagMap create(int size) {
    return new OptimizedTagMap();
  }

  @Override
  public OptimizedTagMap empty() {
    return OptimizedTagMap.EMPTY;
  }
}

final class LegacyTagMapFactory extends TagMapFactory<LegacyTagMap> {
  static final LegacyTagMapFactory INSTANCE = new LegacyTagMapFactory();

  private LegacyTagMapFactory() {}

  @Override
  public LegacyTagMap create() {
    return new LegacyTagMap();
  }

  @Override
  public LegacyTagMap create(int size) {
    return new LegacyTagMap(size);
  }

  @Override
  public LegacyTagMap empty() {
    return LegacyTagMap.EMPTY;
  }
}

/*
 * For memory efficiency, OptimizedTagMap uses a rather complicated bucket system.
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
final class OptimizedTagMap implements TagMap {
  // Using special constructor that creates a frozen view of an existing array
  // Bucket calculation requires that array length is a power of 2
  // e.g. size 0 will not work, it results in ArrayIndexOutOfBoundsException, but size 1 does
  static final OptimizedTagMap EMPTY = new OptimizedTagMap(new Object[1], 0);

  private final Object[] buckets;
  private int size;
  private boolean frozen;

  public OptimizedTagMap() {
    // needs to be a power of 2 for bucket masking calculation to work as intended
    this.buckets = new Object[1 << 4];
    this.size = 0;
    this.frozen = false;
  }

  /** Used for inexpensive immutable */
  private OptimizedTagMap(Object[] buckets, int size) {
    this.buckets = buckets;
    this.size = size;
    this.frozen = true;
  }

  @Override
  public boolean isOptimized() {
    return true;
  }

  @Override
  public int size() {
    return this.size;
  }

  @Override
  public boolean isEmpty() {
    return (this.size == 0);
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

  @Override
  public Iterator<String> tagIterator() {
    return new KeysIterator(this);
  }

  @Override
  public Collection<Object> values() {
    return new Values(this);
  }

  @Override
  public Iterator<Object> valueIterator() {
    return new ValuesIterator(this);
  }

  @Override
  public Set<java.util.Map.Entry<String, Object>> entrySet() {
    return new Entries(this);
  }

  @Override
  public Entry getEntry(String tag) {
    Object[] thisBuckets = this.buckets;

    int hash = TagMap.Entry._hash(tag);
    int bucketIndex = hash & (thisBuckets.length - 1);

    Object bucket = thisBuckets[bucketIndex];
    if (bucket == null) {
      return null;
    } else if (bucket instanceof Entry) {
      Entry tagEntry = (Entry) bucket;
      if (tagEntry.matches(tag)) return tagEntry;
    } else if (bucket instanceof BucketGroup) {
      BucketGroup lastGroup = (BucketGroup) bucket;

      Entry tagEntry = lastGroup.findInChain(hash, tag);
      return tagEntry;
    }
    return null;
  }

  @Deprecated
  @Override
  public Object put(String tag, Object value) {
    TagMap.Entry entry = this.getAndSet(Entry.newAnyEntry(tag, value));
    return entry == null ? null : entry.objectValue();
  }

  @Override
  public void set(TagMap.EntryReader newEntryReader) {
    this.getAndSet(newEntryReader.entry());
  }

  @Override
  public void set(String tag, Object value) {
    this.getAndSet(Entry.newAnyEntry(tag, value));
  }

  @Override
  public void set(String tag, CharSequence value) {
    this.getAndSet(Entry.newObjectEntry(tag, value));
  }

  @Override
  public void set(String tag, boolean value) {
    this.getAndSet(Entry.newBooleanEntry(tag, value));
  }

  @Override
  public void set(String tag, int value) {
    this.getAndSet(Entry.newIntEntry(tag, value));
  }

  @Override
  public void set(String tag, long value) {
    this.getAndSet(Entry.newLongEntry(tag, value));
  }

  @Override
  public void set(String tag, float value) {
    this.getAndSet(Entry.newFloatEntry(tag, value));
  }

  @Override
  public void set(String tag, double value) {
    this.getAndSet(Entry.newDoubleEntry(tag, value));
  }

  @Override
  public Entry getAndSet(Entry newEntry) {
    this.checkWriteAccess();

    Object[] thisBuckets = this.buckets;

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

  @Override
  public Entry getAndSet(String tag, Object value) {
    return this.getAndSet(Entry.newAnyEntry(tag, value));
  }

  @Override
  public Entry getAndSet(String tag, CharSequence value) {
    return this.getAndSet(Entry.newObjectEntry(tag, value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, boolean value) {
    return this.getAndSet(Entry.newBooleanEntry(tag, value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, int value) {
    return this.getAndSet(Entry.newIntEntry(tag, value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, long value) {
    return this.getAndSet(Entry.newLongEntry(tag, value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, float value) {
    return this.getAndSet(Entry.newFloatEntry(tag, value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, double value) {
    return this.getAndSet(Entry.newDoubleEntry(tag, value));
  }

  public void putAll(Map<? extends String, ? extends Object> map) {
    this.checkWriteAccess();

    if (map instanceof OptimizedTagMap) {
      this.putAllOptimizedMap((OptimizedTagMap) map);
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

    if (that instanceof OptimizedTagMap) {
      this.putAllOptimizedMap((OptimizedTagMap) that);
    } else {
      this.putAllUnoptimizedMap(that);
    }
  }

  private void putAllOptimizedMap(OptimizedTagMap that) {
    if (this.size == 0) {
      this.putAllIntoEmptyMap(that);
    } else {
      this.putAllMerge(that);
    }
  }

  private void putAllMerge(OptimizedTagMap that) {
    Object[] thisBuckets = this.buckets;
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
  }

  /*
   * Specially optimized version of putAll for the common case of destination map being empty
   */
  private void putAllIntoEmptyMap(OptimizedTagMap that) {
    Object[] thisBuckets = this.buckets;
    Object[] thatBuckets = that.buckets;

    // Check against both thisBuckets.length && thatBuckets.length is to help the JIT do bound check
    // elimination
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

  @Override
  public Entry getAndRemove(String tag) {
    this.checkWriteAccess();

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

  @Override
  public TagMap copy() {
    OptimizedTagMap copy = new OptimizedTagMap();
    copy.putAllIntoEmptyMap(this);
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

  @Override
  public Stream<EntryReader> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Override
  public void forEach(Consumer<? super TagMap.EntryReader> consumer) {
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
  }

  @Override
  public <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
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
  }

  @Override
  public <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
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
  }

  public void clear() {
    this.checkWriteAccess();

    Arrays.fill(this.buckets, null);
    this.size = 0;
  }

  public OptimizedTagMap freeze() {
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

    if (this.size != this.computeSize()) {
      throw new IllegalStateException("incorrect size");
    }
    if (this.isEmpty() != this.checkIfEmpty()) {
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

    return TagMap.super.compute(key, remappingFunction);
  }

  @Override
  public Object computeIfAbsent(
      String key, Function<? super String, ? extends Object> mappingFunction) {
    this.checkWriteAccess();

    return TagMap.super.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Object computeIfPresent(
      String key, BiFunction<? super String, ? super Object, ? extends Object> remappingFunction) {
    this.checkWriteAccess();

    return TagMap.super.computeIfPresent(key, remappingFunction);
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
    private final Object[] buckets;

    private Entry nextEntry;

    private int bucketIndex = -1;

    private BucketGroup group = null;
    private int groupIndex = 0;

    IteratorBase(OptimizedTagMap map) {
      this.buckets = map.buckets;
    }

    public final boolean hasNext() {
      if (this.nextEntry != null) return true;

      while (this.bucketIndex < this.buckets.length) {
        this.nextEntry = this.advance();
        if (this.nextEntry != null) return true;
      }

      return false;
    }

    final Entry nextEntryOrThrowNoSuchElement() {
      if (this.nextEntry != null) {
        Entry nextEntry = this.nextEntry;
        this.nextEntry = null;
        return nextEntry;
      }

      if (this.hasNext()) {
        return this.nextEntry;
      } else {
        throw new NoSuchElementException();
      }
    }

    final Entry nextEntryOrNull() {
      if (this.nextEntry != null) {
        Entry nextEntry = this.nextEntry;
        this.nextEntry = null;
        return nextEntry;
      }

      return this.hasNext() ? this.nextEntry : null;
    }

    private final Entry advance() {
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
    EntryReaderIterator(OptimizedTagMap map) {
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
    private final OptimizedTagMap map;

    Entries(OptimizedTagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.computeSize();
    }

    @Override
    public boolean isEmpty() {
      return this.map.checkIfEmpty();
    }

    @Override
    public Iterator<Map.Entry<String, Object>> iterator() {
      @SuppressWarnings({"rawtypes", "unchecked"})
      Iterator<Map.Entry<String, Object>> iter = (Iterator) this.map.iterator();
      return iter;
    }
  }

  static final class Keys extends AbstractSet<String> {
    final OptimizedTagMap map;

    Keys(OptimizedTagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.computeSize();
    }

    @Override
    public boolean isEmpty() {
      return this.map.checkIfEmpty();
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
    KeysIterator(OptimizedTagMap map) {
      super(map);
    }

    @Override
    public String next() {
      return this.nextEntryOrThrowNoSuchElement().tag();
    }
  }

  static final class Values extends AbstractCollection<Object> {
    final OptimizedTagMap map;

    Values(OptimizedTagMap map) {
      this.map = map;
    }

    @Override
    public int size() {
      return this.map.computeSize();
    }

    @Override
    public boolean isEmpty() {
      return this.map.checkIfEmpty();
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
    ValuesIterator(OptimizedTagMap map) {
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

final class LegacyTagMap extends HashMap<String, Object> implements TagMap {
  private static final long serialVersionUID = 77473435283123683L;

  static final LegacyTagMap EMPTY = new LegacyTagMap().freeze();

  private boolean frozen = false;

  LegacyTagMap() {
    super();
  }

  LegacyTagMap(int capacity) {
    super(capacity);
  }

  LegacyTagMap(LegacyTagMap that) {
    super(that);
  }

  @Override
  public boolean isOptimized() {
    return false;
  }

  @Override
  public void clear() {
    this.checkWriteAccess();

    super.clear();
  }

  public LegacyTagMap freeze() {
    this.frozen = true;

    return this;
  }

  public boolean isFrozen() {
    return this.frozen;
  }

  public void checkWriteAccess() {
    if (this.frozen) throw new IllegalStateException("TagMap frozen");
  }

  @Override
  public TagMap copy() {
    return new LegacyTagMap(this);
  }

  @Override
  public Iterator<String> tagIterator() {
    return this.keySet().iterator();
  }

  @Override
  public Iterator<Object> valueIterator() {
    return this.values().iterator();
  }

  @Override
  public void fillMap(Map<? super String, Object> map) {
    map.putAll(this);
  }

  @Override
  public void fillStringMap(Map<? super String, ? super String> stringMap) {
    for (Map.Entry<String, Object> entry : this.entrySet()) {
      stringMap.put(entry.getKey(), entry.getValue().toString());
    }
  }

  @Override
  public void forEach(Consumer<? super TagMap.EntryReader> consumer) {
    EntryReadingHelper entryReadingHelper = new EntryReadingHelper();

    // TODO: optimize to take advantage of EntryReader
    for (Map.Entry<String, Object> entry : this.entrySet()) {
      entryReadingHelper.set(entry);

      consumer.accept(entryReadingHelper);
    }
  }

  @Override
  public <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    EntryReadingHelper entryReadingHelper = new EntryReadingHelper();

    for (Map.Entry<String, Object> entry : this.entrySet()) {
      entryReadingHelper.set(entry);

      consumer.accept(thisObj, entryReadingHelper);
    }
  }

  @Override
  public <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
    EntryReadingHelper entryReadingHelper = new EntryReadingHelper();

    for (Map.Entry<String, Object> entry : this.entrySet()) {
      entryReadingHelper.set(entry);

      consumer.accept(thisObj, otherObj, entryReadingHelper);
    }
  }

  @Override
  public TagMap.Entry getAndSet(String tag, Object value) {
    Object prior = this.put(tag, value);
    return prior == null ? null : TagMap.Entry.newAnyEntry(tag, prior);
  }

  @Override
  public TagMap.Entry getAndSet(String tag, CharSequence value) {
    Object prior = this.put(tag, value);
    return prior == null ? null : TagMap.Entry.newAnyEntry(tag, prior);
  }

  @Override
  public TagMap.Entry getAndSet(String tag, boolean value) {
    return this.getAndSet(tag, Boolean.valueOf(value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, double value) {
    return this.getAndSet(tag, Double.valueOf(value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, float value) {
    return this.getAndSet(tag, Float.valueOf(value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, int value) {
    return this.getAndSet(tag, Integer.valueOf(value));
  }

  @Override
  public TagMap.Entry getAndSet(String tag, long value) {
    return this.getAndSet(tag, Long.valueOf(value));
  }

  @Override
  public TagMap.Entry getAndSet(TagMap.Entry newEntry) {
    return this.getAndSet(newEntry.tag(), newEntry.objectValue());
  }

  @Override
  public TagMap.Entry getAndRemove(String tag) {
    Object prior = this.remove((Object) tag);
    return prior == null ? null : TagMap.Entry.newAnyEntry(tag, prior);
  }

  @Override
  public Object getObject(String tag) {
    return this.get(tag);
  }

  @Override
  public boolean getBoolean(String tag) {
    return this.getBooleanOrDefault(tag, false);
  }

  @Override
  public boolean getBooleanOrDefault(String tag, boolean defaultValue) {
    Object result = this.get(tag);
    if (result == null) {
      return defaultValue;
    } else if (result instanceof Boolean) {
      return (Boolean) result;
    } else if (result instanceof Number) {
      Number number = (Number) result;
      return (number.intValue() != 0);
    } else {
      // deliberately doesn't use defaultValue
      return true;
    }
  }

  @Override
  public double getDouble(String tag) {
    return this.getDoubleOrDefault(tag, 0D);
  }

  @Override
  public double getDoubleOrDefault(String tag, double defaultValue) {
    Object value = this.get(tag);
    if (value == null) {
      return defaultValue;
    } else if (value instanceof Number) {
      return ((Number) value).doubleValue();
    } else if (value instanceof Boolean) {
      return ((Boolean) value) ? 1D : 0D;
    } else {
      // deliberately doesn't use defaultValue
      return 0D;
    }
  }

  @Override
  public long getLong(String tag) {
    return this.getLongOrDefault(tag, 0L);
  }

  public long getLongOrDefault(String tag, long defaultValue) {
    Object value = this.get(tag);
    if (value == null) {
      return defaultValue;
    } else if (value instanceof Number) {
      return ((Number) value).longValue();
    } else if (value instanceof Boolean) {
      return ((Boolean) value) ? 1L : 0L;
    } else {
      // deliberately doesn't use defaultValue
      return 0L;
    }
  }

  @Override
  public float getFloat(String tag) {
    return this.getFloatOrDefault(tag, 0F);
  }

  @Override
  public float getFloatOrDefault(String tag, float defaultValue) {
    Object value = this.get(tag);
    if (value == null) {
      return defaultValue;
    } else if (value instanceof Number) {
      return ((Number) value).floatValue();
    } else if (value instanceof Boolean) {
      return ((Boolean) value) ? 1F : 0F;
    } else {
      // deliberately doesn't use defaultValue
      return 0F;
    }
  }

  @Override
  public int getInt(String tag) {
    return this.getIntOrDefault(tag, 0);
  }

  @Override
  public int getIntOrDefault(String tag, int defaultValue) {
    Object value = this.get(tag);
    if (value == null) {
      return defaultValue;
    } else if (value instanceof Number) {
      return ((Number) value).intValue();
    } else if (value instanceof Boolean) {
      return ((Boolean) value) ? 1 : 0;
    } else {
      // deliberately doesn't use defaultValue
      return 0;
    }
  }

  @Override
  public String getString(String tag) {
    Object value = this.get(tag);
    return value == null ? null : value.toString();
  }

  @Override
  public TagMap.Entry getEntry(String tag) {
    Object value = this.get(tag);
    return value == null ? null : TagMap.Entry.newAnyEntry(tag, value);
  }

  @Override
  public void set(String tag, boolean value) {
    this.put(tag, Boolean.valueOf(value));
  }

  @Override
  public void set(String tag, CharSequence value) {
    this.put(tag, value);
  }

  @Override
  public void set(String tag, double value) {
    this.put(tag, Double.valueOf(value));
  }

  @Override
  public void set(String tag, float value) {
    this.put(tag, Float.valueOf(value));
  }

  @Override
  public void set(String tag, int value) {
    this.put(tag, Integer.valueOf(value));
  }

  @Override
  public void set(String tag, long value) {
    this.put(tag, Long.valueOf(value));
  }

  @Override
  public void set(String tag, Object value) {
    this.put(tag, value);
  }

  @Override
  public void set(TagMap.EntryReader newEntryReader) {
    this.put(newEntryReader.tag(), newEntryReader.objectValue());
  }

  @Override
  public Object put(String key, Object value) {
    this.checkWriteAccess();

    return super.put(key, value);
  }

  @Override
  public void putAll(Map<? extends String, ? extends Object> m) {
    this.checkWriteAccess();

    super.putAll(m);
  }

  @Override
  public void putAll(TagMap that) {
    this.putAll((Map<? extends String, ? extends Object>) that);
  }

  @Override
  public Object remove(Object key) {
    this.checkWriteAccess();

    return super.remove(key);
  }

  @Override
  public boolean remove(Object key, Object value) {
    this.checkWriteAccess();

    return super.remove(key, value);
  }

  @Override
  public boolean remove(String tag) {
    this.checkWriteAccess();

    return (super.remove(tag) != null);
  }

  @Override
  public Object compute(
      String key, BiFunction<? super String, ? super Object, ? extends Object> remappingFunction) {
    this.checkWriteAccess();

    return super.compute(key, remappingFunction);
  }

  @Override
  public Object computeIfAbsent(
      String key, Function<? super String, ? extends Object> mappingFunction) {
    this.checkWriteAccess();

    return super.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Object computeIfPresent(
      String key, BiFunction<? super String, ? super Object, ? extends Object> remappingFunction) {
    this.checkWriteAccess();

    return super.computeIfPresent(key, remappingFunction);
  }

  @Override
  public TagMap immutableCopy() {
    if (this.isEmpty()) {
      return LegacyTagMap.EMPTY;
    } else {
      return this.copy().freeze();
    }
  }

  @Override
  public Iterator<TagMap.EntryReader> iterator() {
    return new IteratorImpl(this);
  }

  @Override
  public Stream<TagMap.EntryReader> stream() {
    return StreamSupport.stream(this.spliterator(), false);
  }

  private static final class IteratorImpl implements Iterator<TagMap.EntryReader> {
    private final Iterator<Map.Entry<String, Object>> wrappedIter;
    private final EntryReadingHelper entryReadingHelper;

    IteratorImpl(LegacyTagMap legacyMap) {
      this.wrappedIter = legacyMap.entrySet().iterator();
      this.entryReadingHelper = new EntryReadingHelper();
    }

    @Override
    public boolean hasNext() {
      return this.wrappedIter.hasNext();
    }

    @Override
    public TagMap.EntryReader next() {
      Map.Entry<String, Object> entry = this.wrappedIter.next();
      this.entryReadingHelper.set(entry.getKey(), entry.getValue());

      return this.entryReadingHelper;
    }
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
    } else if (value instanceof Character) {
      return TagMap.EntryReader.OBJECT;
    } else {
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
    boolean isSupportedPrimitive = (value instanceof Integer)
        || (value instanceof Long)
        || (value instanceof Double)
        || (value instanceof Float)
        || (value instanceof Boolean)
        || (value instanceof Short)
        || (value instanceof Byte);
    
    // Char is just treated as Object
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
      // the actual value would be less than Integer.MIN_VALUE, so using doubleValue.
      
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
