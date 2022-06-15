package datadog.trace.agent.tooling.bytebuddy.tiered;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.WeakCache;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/** Shares type information using a single cache across multiple classloaders. */
public final class TypeInfoCache<T> {
  private static final int CONCURRENCY_LEVEL =
      Math.max(8, Runtime.getRuntime().availableProcessors());

  private final ConcurrentMap<String, SharedTypeInfo<T>> sharedTypeInfo;

  public static final URL UNKNOWN_CLASS_FILE = null;

  public TypeInfoCache(int typeCapacity) {
    sharedTypeInfo =
        new ConcurrentLinkedHashMap.Builder<String, SharedTypeInfo<T>>()
            .maximumWeightedCapacity(typeCapacity)
            .concurrencyLevel(CONCURRENCY_LEVEL)
            .build();
  }

  /**
   * Finds the most recently shared information for the named type.
   *
   * <p>When multiple types exist with the same name only one type will be cached at a time. Callers
   * can compare the originating classloader and class file resource to help disambiguate results.
   */
  public SharedTypeInfo<T> find(String name) {
    return sharedTypeInfo.get(name);
  }

  /**
   * Shares information for the named type, replacing any previously shared details.
   *
   * @return previously shared information for the named type
   */
  public SharedTypeInfo<T> share(String name, ClassLoader loader, URL classFile, T typeInfo) {
    return this.sharedTypeInfo.put(
        name, new SharedTypeInfo<>(loaderId(loader), classFile, typeInfo));
  }

  private static LoaderId loaderId(ClassLoader loader) {
    return BOOTSTRAP_LOADER == loader
        ? BOOTSTRAP_LOADER_ID
        : loaderIds.computeIfAbsent(loader, newLoaderId);
  }

  static final ClassLoader BOOTSTRAP_LOADER = null;
  static final LoaderId BOOTSTRAP_LOADER_ID = null;

  private static final Function<ClassLoader, LoaderId> newLoaderId =
      new Function<ClassLoader, LoaderId>() {
        @Override
        public LoaderId apply(ClassLoader input) {
          return new LoaderId(input);
        }
      };

  private static final WeakCache<ClassLoader, LoaderId> loaderIds = WeakCaches.newWeakCache(64);

  /** Supports classloader comparisons without strongly referencing the classloader. */
  private static final class LoaderId extends WeakReference<ClassLoader> {
    private final int loaderHash;

    LoaderId(ClassLoader loader) {
      super(loader);
      this.loaderHash = System.identityHashCode(loader);
    }

    boolean sameClassLoader(ClassLoader loader) {
      return loaderHash == System.identityHashCode(loader) && loader == get();
    }
  }

  /** Wraps type information with the classloader and class file resource it originated from. */
  public static class SharedTypeInfo<T> {
    private final LoaderId loaderId;
    private final URL classFile;
    private final T typeInfo;

    SharedTypeInfo(LoaderId loaderId, URL classFile, T typeInfo) {
      this.loaderId = loaderId;
      this.classFile = classFile;
      this.typeInfo = typeInfo;
    }

    public boolean sameClassLoader(ClassLoader loader) {
      return BOOTSTRAP_LOADER_ID == loaderId
          ? BOOTSTRAP_LOADER == loader
          : loaderId.sameClassLoader(loader);
    }

    public boolean sameClassFile(URL classFile) {
      return UNKNOWN_CLASS_FILE != this.classFile
          && UNKNOWN_CLASS_FILE != classFile
          && sameClassFile(this.classFile, classFile);
    }

    public T resolve() {
      return typeInfo;
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
