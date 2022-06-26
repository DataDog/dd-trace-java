package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.ClassFileLocators.classFileLocator;
import static datadog.trace.agent.tooling.bytebuddy.TypeInfoCache.UNKNOWN_CLASS_FILE;
import static datadog.trace.bootstrap.AgentClassLoading.LOCATING_CLASS;
import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.BOOTSTRAP_LOADER;

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache;
import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache.SharedTypeInfo;
import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;
import java.io.Serializable;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context-aware factory that provides different kinds of type descriptions:
 *
 * <ul>
 *   <li>minimally parsed type outlines for matching purposes
 *   <li>fully parsed types for the actual transformations
 * </ul>
 *
 * It's expected that callers will set the appropriate class-loader context before using this
 * type-factory. Outline types are returned for matching until full type parsing is explicitly
 * enabled, once we know we're really transforming the type.
 */
final class TypeFactory {
  private static final Logger log = LoggerFactory.getLogger(TypeFactory.class);

  /** Maintain a reusable type factory for each thread involved in class-loading. */
  static final ThreadLocal<TypeFactory> factory =
      new ThreadLocal<TypeFactory>() {
        @Override
        protected TypeFactory initialValue() {
          return new TypeFactory();
        }
      };

  private static final boolean fallBackToLoadClass = Config.get().isResolverUseLoadClassEnabled();

  private static final Map<String, TypeDescription> primitiveDescriptorTypes = new HashMap<>();

  private static final Map<String, TypeDescription> commonLoadedTypes = new HashMap<>();

  static {
    for (Class<?> primitive :
        new Class<?>[] {
          boolean.class,
          byte.class,
          short.class,
          char.class,
          int.class,
          long.class,
          float.class,
          double.class,
          void.class
        }) {
      TypeDescription primitiveType = TypeDescription.ForLoadedType.of(primitive);
      primitiveDescriptorTypes.put(Type.getDescriptor(primitive), primitiveType);
      commonLoadedTypes.put(primitive.getName(), primitiveType);
    }
    for (TypeDescription loaded :
        new TypeDescription[] {
          TypeDescription.OBJECT,
          TypeDescription.STRING,
          TypeDescription.CLASS,
          TypeDescription.THROWABLE,
          TypeDescription.ForLoadedType.of(Serializable.class),
          TypeDescription.ForLoadedType.of(Cloneable.class)
        }) {
      commonLoadedTypes.put(loaded.getName(), loaded);
    }
  }

  private static final TypeParser outlineTypeParser = new OutlineTypeParser();

  private static final TypeParser fullTypeParser = new FullTypeParser();

  private static final TypeInfoCache<TypeDescription> outlineTypes =
      new TypeInfoCache<>(Config.get().getResolverOutlinePoolSize());

  private static final TypeInfoCache<TypeDescription> fullTypes =
      new TypeInfoCache<>(Config.get().getResolverTypePoolSize());

  /** Small local cache to help deduplicate lookups when matching/transforming. */
  private final DDCache<String, LazyType> deferredTypes = DDCaches.newFixedSizeCache(16);

  private final Function<String, LazyType> deferType =
      new Function<String, LazyType>() {
        @Override
        public LazyType apply(String input) {
          return new LazyType(input);
        }
      };

  boolean createOutlines = true;

  private boolean restoreAfterTransform;

  private ClassLoader originalClassLoader;

  private ClassLoader classLoader;

  private ClassFileLocator classFileLocator;

  private String targetName;

  private byte[] targetBytecode;

  /** Sets the current class-loader context of this type-factory. */
  void switchContext(ClassLoader classLoader) {
    if (this.classLoader != classLoader || null == classFileLocator) {
      this.classLoader = classLoader;
      classFileLocator = classFileLocator(classLoader);
      // clear local type cache whenever the class-loader context changes
      deferredTypes.clear();
    }
  }

  /**
   * New transform request; begins with type matching that only requires outline descriptions.
   *
   * <p>Byte-buddy's circularity lock makes sure we won't have any nested transform calls, but we
   * may be asked to transform support types while deciding which loaded types need re-transforming
   * when first installing the agent. If that happens then we need to remember the original context
   * used for matching and restore it afterwards.
   */
  void beginTransform(String name, byte[] bytecode) {
    targetName = name;
    targetBytecode = bytecode;

    if (null != classFileLocator) {
      originalClassLoader = this.classLoader;
      restoreAfterTransform = true;
    }
  }

  /** Once matching is complete we need full descriptions for the actual transformation. */
  void enableFullDescriptions() {
    createOutlines = false;
  }

  /** Cleans-up local caches to minimise memory use once we're done with the type-factory. */
  void endTransform() {
    if (null == targetName) {
      return; // nothing to clean-up, byte-buddy filtered out this class before matching
    }

    targetName = null;
    targetBytecode = null;
    createOutlines = true;

    if (restoreAfterTransform) {
      restoreAfterTransform = false;
      switchContext(originalClassLoader);
      originalClassLoader = null;
    } else {
      clearReferences();
    }
  }

  void endInstall() {
    clearReferences();
  }

  private void clearReferences() {
    classLoader = null;
    classFileLocator = null;
    deferredTypes.clear();
  }

  static TypeDescription findDescriptor(String descriptor) {
    TypeDescription type;
    int arity = 0;
    while (descriptor.charAt(arity) == '[') {
      arity++;
    }
    if (descriptor.charAt(arity) == 'L') {
      // discard leading 'L' and trailing ';'
      type = findType(descriptor.substring(arity + 1, descriptor.length() - 1).replace('/', '.'));
    } else {
      type = primitiveDescriptorTypes.get(descriptor.substring(arity));
    }
    if (arity > 0 && null != type) {
      type = TypeDescription.ArrayProjection.of(type, arity);
    }
    return type;
  }

  static TypeDescription findType(String name) {
    TypeDescription type = commonLoadedTypes.get(name);
    if (null == type) {
      type = factory.get().deferTypeResolution(name);
    }
    return type;
  }

  private TypeDescription deferTypeResolution(String name) {
    return deferredTypes.computeIfAbsent(name, deferType);
  }

  /** Attempts to resolve the named type using the current context. */
  TypeDescription resolveType(String name) {
    if (null == classFileLocator) {
      return TypeDescription.UNDEFINED;
    }

    TypeDescription type =
        createOutlines
            ? lookupType(name, outlineTypes, outlineTypeParser)
            : lookupType(name, fullTypes, fullTypeParser);

    return null != type ? new CachingType(type) : null;
  }

  /** Looks up the type in the current context before falling back to parsing the class-file. */
  private TypeDescription lookupType(
      String name, TypeInfoCache<TypeDescription> types, TypeParser typeParser) {

    // existing info from same classloader?
    SharedTypeInfo<TypeDescription> typeInfo = types.find(name);
    if (null != typeInfo && typeInfo.sameClassLoader(classLoader)) {
      return typeInfo.get();
    }

    // are we looking up the target of this transformation?
    if (name.equals(targetName)) {
      TypeDescription type = typeParser.parse(targetBytecode);
      types.share(name, classLoader, UNKNOWN_CLASS_FILE, type);
      return type;
    }

    // try to find the class file resource
    ClassFileLocator.Resolution classFileResolution;
    try {
      classFileResolution = classFileLocator.locate(name);
    } catch (Throwable ignored) {
      return null;
    }

    URL classFile =
        classFileResolution instanceof ClassFileLocators.LazyResolution
            ? ((ClassFileLocators.LazyResolution) classFileResolution).url()
            : UNKNOWN_CLASS_FILE;

    // existing info from same class file?
    if (null != typeInfo && typeInfo.sameClassFile(classFile)) {
      return typeInfo.get();
    }

    TypeDescription type = null;

    // try to parse the class file resource
    if (classFileResolution.isResolved()) {
      type = typeParser.parse(classFileResolution.resolve());
    } else if (fallBackToLoadClass) {
      type = loadType(name, typeParser);
    }

    // share result, whether we found it or not
    types.share(name, classLoader, classFile, type);

    return type;
  }

  /** Falls back to loading the class directly; note this will bypass transformation. */
  private TypeDescription loadType(String name, TypeParser typeParser) {
    LOCATING_CLASS.begin();
    try {
      Class<?> loadedType =
          BOOTSTRAP_LOADER == classLoader
              ? Class.forName(name, false, BOOTSTRAP_LOADER)
              : classLoader.loadClass(name);
      log.debug(
          "Direct loadClass type resolution of {} from class loader {} bypasses transformation",
          name,
          classLoader);
      return typeParser.parse(loadedType);
    } catch (Throwable ignored) {
      return null;
    } finally {
      LOCATING_CLASS.end();
    }
  }

  /** Type description that begins with a name and provides more details on-demand. */
  final class LazyType extends WithName {
    TypeDescription delegate;
    boolean isOutline;

    LazyType(String name) {
      super(name);
    }

    @Override
    protected TypeDescription delegate() {
      return doResolve(true);
    }

    TypeDescription doResolve(boolean throwIfMissing) {
      // re-resolve type when switching to full descriptions
      if (null == delegate || createOutlines != isOutline) {
        delegate = resolveType(name);
        isOutline = createOutlines;
        if (throwIfMissing && null == delegate) {
          throw new TypePool.Resolution.NoSuchTypeException(name);
        }
      }
      return delegate;
    }
  }

  /** Type resolution that provides more details on-demand. */
  static final class LazyResolution implements TypePool.Resolution {
    private final LazyType type;

    LazyResolution(LazyType type) {
      this.type = type;
    }

    @Override
    public boolean isResolved() {
      return null != type.doResolve(false);
    }

    @Override
    public TypeDescription resolve() {
      return type;
    }
  }
}
