package datadog.trace.api;

import datadog.apicontract.Restricted;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
  // Built from the JVM's chosen store's createEmpty(); IdStoreFactory explains why that is safe
  // during TagMap's <clinit>, whichever class initializes first.
  public static final TagMap EMPTY = new TagMap(IdStoreFactory.Default.INSTANCE.createEmpty());

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
    return new TagMap(readThroughParent(parent));
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
      // Canonicalize so a removal recorded under an OpenTelemetry rename matches an Entry recorded
      // (via Entry's own constructor) under its Datadog name -- see Ledger#contains/#matches.
      super(KnownTagCodec.canonicalTagName(tag));
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

    /** Returns the known ID for {@link #tag()}, or {@code 0L} for an unknown tag. */
    long tagId();

    /**
     * This entry's tag name in the OpenTelemetry namespace: the rename the registry declares for
     * it, else its Datadog name (pass-through, the default), else — for a custom tag, which the
     * registry does not name at all — {@link #tag()} itself.
     *
     * <p>Never null, which is the point of asking the reader rather than the codec. {@link
     * KnownTagCodec#openTelemetryTagOf} owns the naming policy but returns null for an unknown id,
     * because only the holder of the entry knows the key to fall back to. This completes that one
     * step and nothing more, so the policy still lives in exactly one place.
     */
    default String openTelemetryTag() {
      String otelTag = KnownTagCodec.openTelemetryTagOf(tagId());
      return otelTag != null ? otelTag : tag();
    }

    /**
     * {@link #openTelemetryTag()} on a span of {@code direction} (a {@code
     * KnownTagCodec.DIRECTION_*} constant), which also applies a rename that holds only in that
     * direction: {@code peer.port} is {@code server.port} on a client span.
     */
    default String openTelemetryTag(int direction) {
      long tagId = tagId();
      return tagId == 0 ? tag() : KnownTagCodec.openTelemetryTagOf(tagId, direction);
    }

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
     * Whether {@code value} is treated as "no tag" — a null, or an empty {@link CharSequence}. Set
     * paths that honor the tag-filtering contract (e.g. {@code AgentSpan.setTag}, {@link
     * SpanPrototype}) can gate on this without constructing an Entry — which matters once a dense
     * store makes per-tag Entry allocation the wrong path.
     */
    public static boolean isEmptyValue(Object value) {
      return value == null
          || (value instanceof CharSequence && ((CharSequence) value).length() == 0);
    }

    /**
     * Entry for {@code (tag, value)}, or null when {@code value} is null or an empty {@code
     * CharSequence} -- checked by runtime type, so an empty String passed as {@code Object} skips
     * the same as via the {@link #create(String, CharSequence)} overload.
     */
    @Nullable
    public static final Entry create(@Nonnull String tag, Object value) {
      return isEmptyValue(value) ? null : TagMap.anyEntryFor(tag, value);
    }

    /** If value is non-null, returns a new TagMap.Entry If value is null or empty, returns null */
    @Nullable
    public static final Entry create(@Nonnull String tag, CharSequence value) {
      // NOTE: From the static typing, we know that value is not a primitive box
      return isEmptyValue(value) ? null : TagMap.objectEntryFor(tag, value);
    }

    public static final Entry create(@Nonnull String tag, boolean value) {
      return TagMap.booleanEntryFor(tag, value);
    }

    public static final Entry create(@Nonnull String tag, int value) {
      return TagMap.intEntryFor(tag, value);
    }

    public static final Entry create(@Nonnull String tag, long value) {
      return TagMap.longEntryFor(tag, value);
    }

    public static final Entry create(@Nonnull String tag, float value) {
      return TagMap.floatEntryFor(tag, value);
    }

    public static final Entry create(@Nonnull String tag, double value) {
      return TagMap.doubleEntryFor(tag, value);
    }

    /*
     * Id-keyed counterparts of the create overloads above, for a KnownTags.*_ID: same contract (a
     * null or empty value yields no entry), without the name lookup. An id that names no known tag
     * is rejected.
     */

    /** Entry for a known tag id, or null when {@code value} is null or an empty CharSequence. */
    @Nullable
    public static final Entry create(long tagId, Object value) {
      return isEmptyValue(value) ? null : TagMap.Entry.newAnyEntry(tagId, value);
    }

    /** Entry for a known tag id, or null when {@code value} is null or empty. */
    @Nullable
    public static final Entry create(long tagId, CharSequence value) {
      return isEmptyValue(value) ? null : TagMap.Entry.newObjectEntry(tagId, value);
    }

    public static final Entry create(long tagId, boolean value) {
      return TagMap.Entry.newBooleanEntry(tagId, value);
    }

    public static final Entry create(long tagId, int value) {
      return TagMap.Entry.newIntEntry(tagId, value);
    }

    public static final Entry create(long tagId, long value) {
      return TagMap.Entry.newLongEntry(tagId, value);
    }

    public static final Entry create(long tagId, float value) {
      return TagMap.Entry.newFloatEntry(tagId, value);
    }

    public static final Entry create(long tagId, double value) {
      return TagMap.Entry.newDoubleEntry(tagId, value);
    }

    static Entry newAnyEntry(Map.Entry<? extends String, ? extends Object> entry) {
      return anyEntryFor(entry.getKey(), entry.getValue());
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

    // Id-keyed factories: the tag id already names a canonical tag, so these skip the
    // canonicalizing name lookup the String factories pay.
    static Entry newAnyEntry(long tagId, Object value) {
      return new Entry(tagId, ANY, 0L, value);
    }

    static Entry newObjectEntry(long tagId, Object value) {
      return new Entry(tagId, OBJECT, 0, value);
    }

    static Entry newBooleanEntry(long tagId, boolean value) {
      return new Entry(tagId, BOOLEAN, boolean2Prim(value), Boolean.valueOf(value));
    }

    static Entry newIntEntry(long tagId, int value) {
      return new Entry(tagId, INT, int2Prim(value), null);
    }

    static Entry newLongEntry(long tagId, long value) {
      return new Entry(tagId, LONG, long2Prim(value), null);
    }

    static Entry newFloatEntry(long tagId, float value) {
      return new Entry(tagId, FLOAT, float2Prim(value), null);
    }

    static Entry newDoubleEntry(long tagId, double value) {
      return new Entry(tagId, DOUBLE, double2Prim(value), null);
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
     * The tag's 64-bit hash, computed once at construction. For a known tag it is the tag id, whose
     * serial sits in the high bits, so the upper 32 bits are never zero; for a custom tag it is the
     * name's hash in the low 32 bits, with the upper 32 bits zero. The two can never collide, and
     * tagId() is a field read. See tagHashOf.
     */
    final long tagHash;

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

    /**
     * True for a known tag's tombstone: the marker the hash id store puts in its own buckets to
     * hide an inherited entry it has removed. Never visible outside the store; see {@link
     * #tombstoneOf}.
     */
    final boolean tombstone;

    /** The shared tombstone per known tag, by serial, created on first use. */
    private static final Entry[] TOMBSTONES = new Entry[KnownTagCodec.serialLimit()];

    /**
     * The tombstone for the known tag {@code tagId}: one shared, immutable entry per tag, so
     * tombstoning a known tag allocates nothing after the first time. Racing first uses may each
     * build one; either serves, since a tombstone is told apart by {@link #tombstone}, not
     * identity.
     */
    static Entry tombstoneOf(long tagId) {
      int serial = KnownTagCodec.serialNum(tagId);
      Entry tombstone = TOMBSTONES[serial];
      if (tombstone == null || tombstone.tagHash != tagId) {
        tombstone = new Entry(tagId);
        TOMBSTONES[serial] = tombstone;
      }
      return tombstone;
    }

    private Entry(long tagId) {
      super(requireKnownName(tagId));
      this.tagHash = tagId;
      this.tombstone = true;
      this.rawType = OBJECT;
      this.rawPrim = 0L;
      this.rawObj = null;
    }

    /**
     * A custom tag's entry: no registry lookup. A known tag is built from its id instead -- callers
     * that start from a name resolve it once and pick the id or name factory (see {@code
     * TagMap.anyEntryFor}).
     */
    private Entry(String tag, byte type, long prim, Object obj) {
      this(0L, tag, type, prim, obj);
      assert KnownTagCodec.keyOf(tag) == 0 : "'" + tag + "' is a known tag; build it from its id";
    }

    Entry(long tagId, byte type, long prim, Object obj) {
      // Resolve the name once: it both validates the id and names the entry.
      super(requireKnownName(tagId));
      this.tagHash = tagId;
      this.tombstone = false;

      this.rawType = type;
      this.rawPrim = prim;
      this.rawObj = obj;
    }

    /** {@code tagId} is a known id, or 0 for the custom tag {@code customTag}. */
    Entry(long tagId, String customTag, byte type, long prim, Object obj) {
      super(tagId != 0 ? KnownTagCodec.nameOf(tagId) : customTag);
      this.tagHash = tagId != 0 ? tagId : customHash(customTag);
      this.tombstone = false;

      this.rawType = type;
      this.rawPrim = prim;
      this.rawObj = obj;
    }

    static String requireKnownName(long tagId) {
      String name = tagId == 0 ? null : KnownTagCodec.nameOf(tagId);
      if (name == null) {
        throw new IllegalArgumentException("not a known tag id: " + Long.toHexString(tagId));
      }
      return name;
    }

    /** The tag hash a name maps to: its id when known, else its {@link #customHash}. */
    static long tagHashOf(String tag) {
      long tagId = KnownTagCodec.keyOf(tag);
      return tagId != 0 ? tagId : customHash(tag);
    }

    /** A custom tag's hash: the name hash in the low 32 bits, never colliding with a tag id. */
    static long customHash(String tag) {
      return _hash(tag) & 0xFFFFFFFFL;
    }

    /**
     * Folds a tag hash to the 32-bit bucket hash. A tag id's serial is in bits 63-48, so it is
     * folded down from there rather than from bit 32, where a bucket mask would discard it.
     *
     * <p>Never zero: BucketGroup treats a zero hash as a vacant slot. The fold can produce zero
     * when a serial equals the id's flag bits (serial 4 with the trace-level bit, 4), so zero maps
     * to the same nonzero sentinel {@link #_hash} uses.
     */
    static int bucketHash(long tagHash) {
      int hash = (int) (tagHash >>> 48) ^ (int) tagHash;
      return hash == 0 ? 0xDD06 : hash;
    }

    int hash() {
      return bucketHash(this.tagHash);
    }

    @Override
    public long tagId() {
      // A known tag's hash is its id, whose upper 32 bits are never zero; a custom tag's are.
      return (this.tagHash >>> 32) != 0 ? this.tagHash : 0L;
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

    static long boolean2Prim(boolean value) {
      return value ? 1L : 0L;
    }

    private static boolean prim2Boolean(long prim) {
      return (prim != 0L);
    }

    static long int2Prim(int value) {
      return value;
    }

    private static int prim2Int(long prim) {
      return (int) prim;
    }

    static long long2Prim(long value) {
      return value;
    }

    private static long prim2Long(long prim) {
      return prim;
    }

    static long float2Prim(float value) {
      return Float.floatToIntBits(value);
    }

    private static float prim2Float(long prim) {
      return Float.intBitsToFloat((int) prim);
    }

    static long double2Prim(double value) {
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

    Ledger() {
      this(8);
    }

    Ledger(int size) {
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
      return this.recordEntry(anyEntryFor(tag, value));
    }

    public Ledger set(String tag, CharSequence value) {
      return this.recordEntry(objectEntryFor(tag, value));
    }

    public Ledger set(String tag, boolean value) {
      return this.recordEntry(booleanEntryFor(tag, value));
    }

    public Ledger set(String tag, int value) {
      return this.recordEntry(intEntryFor(tag, value));
    }

    public Ledger set(String tag, long value) {
      return this.recordEntry(longEntryFor(tag, value));
    }

    public Ledger set(String tag, float value) {
      return this.recordEntry(floatEntryFor(tag, value));
    }

    public Ledger set(String tag, double value) {
      return this.recordEntry(doubleEntryFor(tag, value));
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
      // Entries and removals are both recorded under their canonical Datadog name (see newAnyEntry
      // et al. and EntryRemoval's own constructor); canonicalize the query the same way or it
      // would miss.
      String canonicalTag = KnownTagCodec.canonicalTagName(tag);

      EntryChange[] thisChanges = this.entryChanges;

      // min is to clamp, so bounds check elimination optimization works
      int lenClamp = Math.min(this.nextPos, thisChanges.length);
      for (int i = 0; i < lenClamp; ++i) {
        if (thisChanges[i].matches(canonicalTag)) return true;
      }
      return false;
    }

    /*
     * Just for testing
     */
    Entry findLastEntry(String tag) {
      String canonicalTag = KnownTagCodec.canonicalTagName(tag);
      EntryChange[] thisChanges = this.entryChanges;

      // min is to clamp, so ArrayBoundsCheckElimination optimization works
      int clampLen = Math.min(this.nextPos, thisChanges.length) - 1;
      for (int i = clampLen; i >= 0; --i) {
        EntryChange thisChange = thisChanges[i];
        if (!thisChange.isRemoval() && thisChange.matches(canonicalTag)) return (Entry) thisChange;
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
   * A TagMap holds its tags in two stores: an IdStore with the known tags by id, and a HashStore
   * with the custom tags by name. Their key spaces are disjoint,
   * so each store reads through to its own parent chain and keeps its own tombstones, and
   * precedence never crosses stores.
   *
   * The custom store is created on the first custom write. Until then, a map with a parent borrows
   * the parent's custom store, read-only (nameStoreBorrowed): the parent is frozen, so its store is
   * stable, and with no local custom state the borrowed store reads exactly as an own one would.
   */

  /** The known tags, by id. Not final only so {@link #clear()} can replace it. */
  IdStore knownStore;

  /**
   * The custom tags, by name: null until the first custom write, or the parent's custom store,
   * borrowed read-only while {@link #nameStoreBorrowed}.
   */
  HashStore nameStore;

  /** True while {@link #nameStore} is the parent's custom store, borrowed until the first write. */
  boolean nameStoreBorrowed;

  private boolean frozen;

  /**
   * Optional frozen parent for read-through. Reads that miss locally fall through to the parent
   * chain (each store reads through to the parent's matching store). Must be frozen when attached,
   * so it is safely shareable. Not final only so {@link #clear()} can detach it (to null); it is
   * otherwise fixed at construction and never re-pointed. Package-visible so same-package tests can
   * assert attach/detach directly.
   */
  @VisibleForTesting TagMap parent;

  public TagMap() {
    this((TagMap) null);
  }

  /**
   * Fresh mutable map that reads through to {@code parent} (may be null). The parent is set here at
   * construction and never re-pointed (only detached to null by {@link #clear()}), so read-through
   * optimizations can treat it as fixed.
   */
  private TagMap(TagMap parent) {
    this.knownStore = IdStore.create(parent);
    this.nameStore = HashStore.inheritedFrom(parent);
    this.nameStoreBorrowed = this.nameStore != null;
    this.frozen = false;
    this.parent = parent;
  }

  /** A map over {@code knownStore} and {@code nameStore}: a copy ({@link #copy()}) or a view. */
  private TagMap(
      TagMap parent, IdStore knownStore, HashStore nameStore, boolean nameStoreBorrowed) {
    this.knownStore = knownStore;
    this.nameStore = nameStore;
    this.nameStoreBorrowed = nameStoreBorrowed;
    this.frozen = false;
    this.parent = parent;
  }

  /** A frozen map over the empty parentless store {@code knownStore}: {@link #EMPTY}. */
  private TagMap(IdStore knownStore) {
    this.knownStore = knownStore;
    this.nameStore = null;
    this.nameStoreBorrowed = false;
    this.frozen = true;
    this.parent = null;
  }

  /**
   * A live view over a span's stores, for the span's internal readers (tag post-processors,
   * serializers) and its snapshot copy; never on the span's own set/get paths. Not thread-safe: use
   * it under the span's tag lock.
   *
   * <p>The span owns the stores, and the view keeps the ones it was created over. A span that later
   * creates its own custom store drops its view and creates a new one. After using the view, the
   * span adopts any custom store the view created ({@link #customStore}), since a reader may write
   * a custom tag.
   */
  @Restricted(allowedIn = {"datadog.trace.core"})
  public static TagMap viewOf(IdStore knownStore, HashStore nameStore, boolean nameStoreBorrowed) {
    return new TagMap(null, knownStore, nameStore, nameStoreBorrowed);
  }

  /**
   * The parent an owner reads through to: {@code parent}, or null when it is null or empty, as
   * {@link #createFromParent} decides.
   *
   * @throws IllegalStateException if {@code parent} is not frozen
   */
  @Restricted(allowedIn = {"datadog.trace.core"})
  public static TagMap readThroughParent(TagMap parent) {
    if (parent == null) {
      return null;
    }
    if (!parent.isFrozen()) {
      throw new IllegalStateException("read-through parent must be frozen");
    }
    // Exact, not isDefinitelyEmpty(): a multi-level parent can be observationally empty while a
    // local level still holds entries (all of them tombstoned by a nearer level). Dropping such a
    // parent keeps isEmpty()'s no-tombstone fast path valid -- an attached parent always
    // contributes at least one visible entry.
    return parent.isEmpty() ? null : parent;
  }

  /**
   * The Entry an id store's {@code reader} denotes, or null, for a caller that keeps it or hands it
   * out. Free for a hash store's reader, which is an Entry; for a dense store's reused flyweight,
   * this builds one.
   */
  private static Entry entryOf(EntryReader reader) {
    return reader == null ? null : reader.entry();
  }

  /** This map's custom store: null, borrowed ({@link #isCustomStoreBorrowed}), or its own. */
  @Restricted(allowedIn = {"datadog.trace.core"})
  public HashStore customStore() {
    return this.nameStore;
  }

  /** Whether {@link #customStore} is the parent's, borrowed read-only. */
  @Restricted(allowedIn = {"datadog.trace.core"})
  public boolean isCustomStoreBorrowed() {
    return this.nameStoreBorrowed;
  }

  /** The custom store to write to: created on the first write, ending any borrowing. */
  HashStore nameStoreForWrite() {
    HashStore nameStore = this.nameStore;
    if (nameStore == null || this.nameStoreBorrowed) {
      // A borrowed store is the nearest ancestor's custom store: read through to it.
      nameStore = HashStore.create(nameStore);
      this.nameStore = nameStore;
      this.nameStoreBorrowed = false;
    }
    return nameStore;
  }

  /** Removes the custom tag {@code tag}, returning the prior visible entry. */
  Entry removeCustom(String tag) {
    HashStore nameStore = this.nameStore;
    if (nameStore == null) {
      return null;
    }
    if (this.nameStoreBorrowed) {
      if (nameStore.get(tag) == null) {
        return null; // nothing to hide: keep borrowing
      }
      nameStore = this.nameStoreForWrite(); // hiding an inherited tag needs a tombstone of our own
    }
    return nameStore.remove(tag);
  }

  /** Whether either store reads through to a parent. */
  private boolean readsThrough() {
    HashStore nameStore = this.nameStore;
    return this.knownStore.hasParent()
        || this.nameStoreBorrowed
        || (nameStore != null && nameStore.parent != null);
  }

  @Override
  public int size() {
    // Exact (Map contract). Under read-through resolves the union; prefer estimateSize() for hints.
    HashStore nameStore = this.nameStore;
    return this.knownStore.size() + (nameStore == null ? 0 : nameStore.size());
  }

  @Override
  public boolean isEmpty() {
    // Exact (Map contract). Under read-through resolves the parent; prefer isDefinitelyEmpty().
    HashStore nameStore = this.nameStore;
    return this.knownStore.isEmpty() && (nameStore == null || nameStore.isEmpty());
  }

  public boolean isDefinitelyEmpty() {
    // Cheap: empty iff no level in the chain holds a local entry (ignores shadowing/tombstones).
    HashStore nameStore = this.nameStore;
    return this.knownStore.isDefinitelyEmpty()
        && (nameStore == null || nameStore.isDefinitelyEmpty());
  }

  public int estimateSize() {
    // Upper bound: sum of every level's local size, ignoring read-through shadowing/removals.
    HashStore nameStore = this.nameStore;
    return this.knownStore.estimateSize() + (nameStore == null ? 0 : nameStore.estimateSize());
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
    // Entries are stored under their canonical Datadog name (see Entry's constructor); a lookup by
    // an OpenTelemetry rename must resolve to the same id, or it would silently miss the entry.
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      return entryOf(this.knownStore.getEntryReader(key));
    }
    HashStore nameStore = this.nameStore;
    return nameStore == null ? null : nameStore.get(tag);
  }

  /** The entry for a known tag id, found by the id itself rather than by resolving a name. */
  public Entry getEntry(long tagId) {
    return entryOf(this.knownStore.getEntryReader(tagId));
  }

  @Deprecated
  @Override
  public Object put(@Nonnull String tag, Object value) {
    TagMap.Entry entry = this.getAndSet(anyEntryFor(tag, value));
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

  /*
   * Entry factories for callers that start from a name: resolve it once, then build a known tag
   * from its id or a custom tag from its name. (Entry's own name factories are custom-only.)
   */
  static Entry anyEntryFor(String tag, Object value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newAnyEntry(key, value) : Entry.newAnyEntry(tag, value);
  }

  static Entry objectEntryFor(String tag, CharSequence value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newObjectEntry(key, value) : Entry.newObjectEntry(tag, value);
  }

  static Entry booleanEntryFor(String tag, boolean value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newBooleanEntry(key, value) : Entry.newBooleanEntry(tag, value);
  }

  static Entry intEntryFor(String tag, int value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newIntEntry(key, value) : Entry.newIntEntry(tag, value);
  }

  static Entry longEntryFor(String tag, long value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newLongEntry(key, value) : Entry.newLongEntry(tag, value);
  }

  static Entry floatEntryFor(String tag, float value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newFloatEntry(key, value) : Entry.newFloatEntry(tag, value);
  }

  static Entry doubleEntryFor(String tag, double value) {
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? Entry.newDoubleEntry(key, value) : Entry.newDoubleEntry(tag, value);
  }

  public void set(@Nonnull String tag, @Nonnull Object value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newAnyEntry(key, value));
    } else {
      this.putCustom(Entry.newAnyEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, @Nonnull CharSequence value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newObjectEntry(key, value));
    } else {
      this.putCustom(Entry.newObjectEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, boolean value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newBooleanEntry(key, value));
    } else {
      this.putCustom(Entry.newBooleanEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, int value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newIntEntry(key, value));
    } else {
      this.putCustom(Entry.newIntEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, long value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newLongEntry(key, value));
    } else {
      this.putCustom(Entry.newLongEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, float value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newFloatEntry(key, value));
    } else {
      this.putCustom(Entry.newFloatEntry(tag, value));
    }
  }

  public void set(@Nonnull String tag, double value) {
    long key = KnownTagCodec.keyOf(tag);
    if (key != 0) {
      this.putKnown(Entry.newDoubleEntry(key, value));
    } else {
      this.putCustom(Entry.newDoubleEntry(tag, value));
    }
  }

  /*
   * Id-keyed setters: the caller passes a KnownTags.*_ID, so the entry is built under the tag's
   * canonical name without the name lookup the String setters pay. The id must name a known tag;
   * custom tags have no id and use the String setters.
   */

  public void set(long tagId, @Nonnull Object value) {
    this.putKnown(Entry.newAnyEntry(tagId, value));
  }

  public void set(long tagId, @Nonnull CharSequence value) {
    this.putKnown(Entry.newObjectEntry(tagId, value));
  }

  public void set(long tagId, boolean value) {
    this.putKnown(Entry.newBooleanEntry(tagId, value));
  }

  public void set(long tagId, int value) {
    this.putKnown(Entry.newIntEntry(tagId, value));
  }

  public void set(long tagId, long value) {
    this.putKnown(Entry.newLongEntry(tagId, value));
  }

  public void set(long tagId, float value) {
    this.putKnown(Entry.newFloatEntry(tagId, value));
  }

  public void set(long tagId, double value) {
    this.putKnown(Entry.newDoubleEntry(tagId, value));
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
    this.checkWriteAccess();

    return newEntry.tagId() != 0
        ? entryOf(this.knownStore.getAndPut(newEntry))
        : this.nameStoreForWrite().getAndPut(newEntry);
  }

  /**
   * Inserts or replaces a local entry, returning the replaced local Entry (or null if none). Does
   * NOT consult the read-through parent -- the {@code set(...)} methods use this so they never pay
   * for a prior-value lookup they discard; {@link #getAndSet(Entry)} layers the parent fallback on
   * top.
   */
  Entry putEntry(@Nonnull Entry newEntry) {
    this.checkWriteAccess();

    return newEntry.tagId() != 0
        ? entryOf(this.knownStore.put(newEntry))
        : this.nameStoreForWrite().put(newEntry);
  }

  /** {@link #putEntry} for an entry known to be a known tag's. */
  void putKnown(Entry knownEntry) {
    this.checkWriteAccess();

    this.knownStore.put(knownEntry);
  }

  /** {@link #putEntry} for an entry known to be a custom tag's. */
  void putCustom(Entry customEntry) {
    this.checkWriteAccess();

    this.nameStoreForWrite().put(customEntry);
  }

  public Entry getAndSet(@Nonnull String tag, Object value) {
    return this.getAndSet(anyEntryFor(tag, value));
  }

  public Entry getAndSet(@Nonnull String tag, CharSequence value) {
    return this.getAndSet(objectEntryFor(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, boolean value) {
    return this.getAndSet(booleanEntryFor(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, int value) {
    return this.getAndSet(intEntryFor(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, long value) {
    return this.getAndSet(longEntryFor(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, float value) {
    return this.getAndSet(floatEntryFor(tag, value));
  }

  public TagMap.Entry getAndSet(@Nonnull String tag, double value) {
    return this.getAndSet(doubleEntryFor(tag, value));
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
    if (that.readsThrough()) {
      // read-through source: the bucket-copy paths only see that's local entries, so they would
      // drop entries visible only through that's ancestor chain (and ignore its tombstones).
      // Union-copy the full visible set instead -- still shares the source Entry objects.
      that.forEach(this, (self, entry) -> self.set(entry));
      return;
    }
    this.knownStore.putAllLocal(that.knownStore);
    HashStore thatNames = that.nameStore;
    if (thatNames != null && thatNames.count != 0) {
      this.nameStoreForWrite().putAllLocal(thatNames);
    }
  }

  public void fillMap(Map<? super String, Object> map) {
    if (this.readsThrough()) {
      // read-through source: the bucket-only loop below sees just local entries, so it would drop
      // tags visible only through the parent chain (and ignore shadowing/tombstones). Walk the full
      // visible union instead -- mirrors putAllOptimizedMap.
      this.forEach(map, (m, entry) -> m.put(entry.tag(), entry.objectValue()));
      return;
    }
    this.knownStore.fillMapLocal(map);
    if (this.nameStore != null) {
      this.nameStore.fillMapLocal(map);
    }
  }

  public void fillStringMap(Map<? super String, ? super String> stringMap) {
    if (this.readsThrough()) {
      // read-through source: walk the visible union so parent-only tags aren't dropped -- see
      // fillMap.
      this.forEach(stringMap, (m, entry) -> m.put(entry.tag(), entry.stringValue()));
      return;
    }
    this.knownStore.fillStringMapLocal(stringMap);
    if (this.nameStore != null) {
      this.nameStore.fillStringMapLocal(stringMap);
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

    // See getEntry: entries are stored under their canonical Datadog name, so a removal by an
    // OpenTelemetry rename must resolve to the id first to find (and tombstone) the right entry.
    long key = KnownTagCodec.keyOf(tag);
    return key != 0 ? entryOf(this.knownStore.remove(key)) : this.removeCustom(tag);
  }

  /** Removes the entry for a known tag id; see {@link #getEntry(long)}. */
  public Entry getAndRemove(long tagId) {
    this.checkWriteAccess();

    return entryOf(this.knownStore.remove(tagId));
  }

  public TagMap copy() {
    // The same (frozen, shared) parent, fixed at construction, over a copy of this map's own
    // entries and tombstones. The copy stays independently mutable (writes land on its local
    // store, never the shared parent).
    HashStore nameStore = this.nameStore;
    boolean borrowed = this.nameStoreBorrowed;
    return new TagMap(
        this.parent,
        this.knownStore.copy(),
        nameStore == null || borrowed ? nameStore : nameStore.copy(),
        borrowed);
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
    this.knownStore.forEach(consumer);
    HashStore nameStore = this.nameStore;
    if (nameStore != null) {
      nameStore.forEach(consumer);
    }
  }

  public <T> void forEach(T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    this.knownStore.forEach(thisObj, consumer);
    HashStore nameStore = this.nameStore;
    if (nameStore != null) {
      nameStore.forEach(thisObj, consumer);
    }
  }

  public <T, U> void forEach(
      T thisObj, U otherObj, TriConsumer<T, U, ? super TagMap.EntryReader> consumer) {
    this.knownStore.forEach(thisObj, otherObj, consumer);
    HashStore nameStore = this.nameStore;
    if (nameStore != null) {
      nameStore.forEach(thisObj, otherObj, consumer);
    }
  }

  public void clear() {
    this.checkWriteAccess();

    // clear() removes ALL mappings, including any inherited through read-through. Fresh parentless
    // stores (rather than tombstoning every inherited key) are simpler and cheaper, and leave an
    // empty, parent-less map. Detach is one-way -- the parent is never re-pointed.
    this.knownStore = IdStoreFactory.Default.INSTANCE.create(null);
    this.nameStore = null;
    this.nameStoreBorrowed = false;
    this.parent = null;
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
    this.knownStore.checkIntegrity();
    if (this.nameStore != null && !this.nameStoreBorrowed) {
      this.nameStore.checkIntegrity();
    }
  }

  /** The local entries, recomputed from both stores' structure. */
  int computeSize() {
    HashStore nameStore = this.nameStore;
    return this.knownStore.computeSize()
        + (nameStore == null || this.nameStoreBorrowed ? 0 : nameStore.computeSize());
  }

  boolean checkIfEmpty() {
    return this.computeSize() == 0;
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

    StringBuilder builder = new StringBuilder(128);
    builder.append('{');
    for (EntryReader entry : this) {
      if (first) {
        first = false;
      } else {
        builder.append(", ");
      }

      builder.append(entry.tag()).append('=').append(entry.stringValue());
    }
    builder.append('}');
    return builder.toString();
  }

  /**
   * toString that more visibility into the internal structure of TagMap - primarily for deep
   * debugging
   */
  String toInternalString() {
    HashStore nameStore = this.nameStore;
    return "knownStore:\n"
        + this.knownStore.toInternalString()
        + "nameStore:\n"
        + (nameStore == null ? "none\n" : nameStore.toInternalString());
  }

  abstract static class IteratorBase {
    // Two phases: the id store's visible entries, through its own cursor, then the custom store's
    // chain -- the leaf's buckets first, then each ancestor's in turn (read-through union).
    private IdStore.Cursor knownCursor; // null once the id phase is done
    private final HashStore nameLeaf;
    private HashStore nameLevel; // the custom level being walked; null before and after
    private HashSupport.BucketWalker walker;

    private EntryReader nextEntry;

    IteratorBase(TagMap map) {
      this.knownCursor = map.knownStore.cursor();
      this.nameLeaf = map.nameStore;
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

    private EntryReader advance() {
      IdStore.Cursor knownCursor = this.knownCursor;
      if (knownCursor != null) {
        EntryReader reader = knownCursor.next();
        if (reader != null) {
          return reader;
        }
        this.knownCursor = null;
        if (this.nameLeaf == null) {
          return null;
        }
        this.nameLevel = this.nameLeaf;
        this.walker = new HashSupport.BucketWalker();
        this.walker.reset(this.nameLeaf.buckets);
      }

      HashStore level = this.nameLevel;
      while (level != null) {
        Entry entry = this.walker.next();
        if (entry != null) {
          // leaf entries emit as-is; ancestor entries only if not shadowed by a nearer level
          if (level == this.nameLeaf || this.nameLeaf.parentEntryVisible(entry, level)) {
            return entry;
          }
          continue;
        }
        level = level.parent;
        this.nameLevel = level;
        if (level != null) {
          this.walker.reset(level.buckets);
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

    /**
     * {@link #findInChain} for a known tag: matches on the entry's id, so it needs no name. Only an
     * entry stored under {@code tagId} has that id, and the hash check filters first.
     */
    Entry findInChainById(int hash, long tagId) {
      for (BucketGroup curGroup = this; curGroup != null; curGroup = curGroup.prev) {
        Entry curEntry = curGroup._findById(hash, tagId);
        if (curEntry != null) return curEntry;
      }
      return null;
    }

    Entry _findById(int hash, long tagId) {
      if (this.hash0 == hash && this.entry0.tagHash == tagId) {
        return this.entry0;
      } else if (this.hash1 == hash && this.entry1.tagHash == tagId) {
        return this.entry1;
      } else if (this.hash2 == hash && this.entry2.tagHash == tagId) {
        return this.entry2;
      } else if (this.hash3 == hash && this.entry3.tagHash == tagId) {
        return this.entry3;
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
      StringBuilder builder = new StringBuilder(32);
      builder.append('[');
      for (int i = 0; i < BucketGroup.LEN; ++i) {
        if (i != 0) builder.append(", ");

        builder.append(this._entryAt(i));
      }
      builder.append(']');
      return builder.toString();
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
   * Map entries for the {@link Map} view: an Entry is its own, while a flyweight reader (a dense
   * store's) builds one, since a caller may keep it.
   */
  static final class EntriesIterator extends IteratorBase
      implements Iterator<Map.Entry<String, Object>> {
    EntriesIterator(TagMap map) {
      super(map);
    }

    @Override
    public Map.Entry<String, Object> next() {
      return this.nextEntryOrThrowNoSuchElement().mapEntry();
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
