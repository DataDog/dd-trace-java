package datadog.trace.agent.tooling.bytebuddy;

import java.net.URL;
import java.util.Arrays;
import java.util.Objects;

/** Shares type information using a single cache across multiple classloaders. */
public final class TypeInfoCache<T> {
  public static final URL UNKNOWN_CLASS_FILE = null;

  // limit allowed capacities as descriptions are not small
  private static final int MAX_CAPACITY = 1 << 16;
  private static final int MIN_CAPACITY = 1 << 4;

  private static final int MAX_HASH_ATTEMPTS = 5;

  private final boolean namesAreUnique;
  private final SharedTypeInfo<T>[] sharedTypeInfo;
  private final int slotMask;

  public TypeInfoCache(int capacity) {
    this(capacity, false);
  }

  @SuppressWarnings("unchecked")
  public TypeInfoCache(int capacity, boolean namesAreUnique) {
    this.namesAreUnique = namesAreUnique;
    if (capacity < MIN_CAPACITY) {
      capacity = MIN_CAPACITY;
    } else if (capacity > MAX_CAPACITY) {
      capacity = MAX_CAPACITY;
    }
    // choose enough slot bits to cover the chosen capacity
    this.slotMask = 0xFFFFFFFF >>> Integer.numberOfLeadingZeros(capacity - 1);
    this.sharedTypeInfo = new SharedTypeInfo[slotMask + 1];
  }

  /**
   * Finds the most recently shared information for the named type.
   *
   * <p>When multiple types exist with the same name only one type will be cached at a time. Callers
   * can compare the originating classloader and class file resource to help disambiguate results.
   */
  public SharedTypeInfo<T> find(String className) {
    int nameHash = className.hashCode();
    for (int i = 1; true; i++) {
      SharedTypeInfo<T> value = sharedTypeInfo[slotMask & nameHash];
      if (null == value) {
        return null;
      } else if (className.equals(value.className)) {
        value.lastUsed = System.currentTimeMillis();
        return value;
      } else if (i == MAX_HASH_ATTEMPTS) {
        return null;
      }
      nameHash = rehash(nameHash);
    }
  }

  /**
   * Shares information for the named type, replacing any previously shared details.
   *
   * @return previously shared information for the named type
   */
  public SharedTypeInfo<T> share(String className, int classLoaderId, URL classFile, T typeInfo) {
    SharedTypeInfo<T> newValue;
    if (namesAreUnique) {
      newValue = new SharedTypeInfo<>(className, typeInfo);
    } else {
      newValue = new DisambiguatingTypeInfo<>(className, typeInfo, classLoaderId, classFile);
    }

    int nameHash = className.hashCode();
    int slot = slotMask & nameHash;

    long leastUsedTime = Long.MAX_VALUE;
    int leastUsedSlot = slot;

    for (int i = 1; true; i++) {
      SharedTypeInfo<T> oldValue = sharedTypeInfo[slot];
      if (null == oldValue || className.equals(oldValue.className)) {
        sharedTypeInfo[slot] = newValue;
        return oldValue;
      } else if (i == MAX_HASH_ATTEMPTS) {
        sharedTypeInfo[leastUsedSlot] = newValue; // overwrite least-recently used
        return null;
      } else if (oldValue.lastUsed < leastUsedTime) {
        leastUsedTime = oldValue.lastUsed;
        leastUsedSlot = slot;
      }
      nameHash = rehash(nameHash);
      slot = slotMask & nameHash;
    }
  }

  /** Clears all type information from the shared cache. */
  public void clear() {
    Arrays.fill(sharedTypeInfo, null);
  }

  private static int rehash(int oldHash) {
    return Integer.reverseBytes(oldHash * 0x9e3775cd) * 0x9e3775cd;
  }

  /** Wraps type information with the name of the class it originated from. */
  public static class SharedTypeInfo<T> {
    final String className;
    private final T typeInfo;

    long lastUsed = System.currentTimeMillis();

    SharedTypeInfo(String className, T typeInfo) {
      this.className = className;
      this.typeInfo = typeInfo;
    }

    public boolean sameClassLoader(int classLoaderId) {
      return true;
    }

    public boolean sameClassFile(URL classFile) {
      return true;
    }

    public final T get() {
      return typeInfo;
    }
  }

  /** Includes the classloader and class file resource it originated from. */
  static final class DisambiguatingTypeInfo<T> extends SharedTypeInfo<T> {
    private final int classLoaderId;
    private final URL classFile;

    DisambiguatingTypeInfo(String className, T typeInfo, int classLoaderId, URL classFile) {
      super(className, typeInfo);
      this.classLoaderId = classLoaderId;
      this.classFile = classFile;
    }

    @Override
    public boolean sameClassLoader(int classLoaderId) {
      return this.classLoaderId == classLoaderId;
    }

    public boolean sameClassFile(URL classFile) {
      return UNKNOWN_CLASS_FILE != classFile
          && UNKNOWN_CLASS_FILE != this.classFile
          && sameClassFile(this.classFile, classFile);
    }

    /** Matches class file resources without triggering network lookups. */
    private static boolean sameClassFile(URL lhs, URL rhs) {
      return Objects.equals(lhs.getFile(), rhs.getFile())
          && Objects.equals(lhs.getRef(), rhs.getRef())
          && Objects.equals(lhs.getAuthority(), rhs.getAuthority())
          && Objects.equals(lhs.getProtocol(), rhs.getProtocol());
    }
  }
}
