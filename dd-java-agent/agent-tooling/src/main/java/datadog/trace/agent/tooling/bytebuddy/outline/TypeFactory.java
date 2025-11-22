package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.ClassFileLocators.classFileLocator;
import static datadog.trace.agent.tooling.bytebuddy.TypeInfoCache.UNKNOWN_CLASS_FILE;
import static datadog.trace.bootstrap.AgentClassLoading.LOCATING_CLASS;
import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.BOOTSTRAP_LOADER;

import datadog.instrument.utils.ClassNameFilter;
import datadog.trace.agent.tooling.InstrumenterMetrics;
import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache;
import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache.SharedTypeInfo;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context-aware thread-local type factory that provides different kinds of type descriptions:
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
  static final ThreadLocal<TypeFactory> typeFactory = ThreadLocal.withInitial(TypeFactory::new);

  private static final boolean fallBackToLoadClass =
      InstrumenterConfig.get().isResolverUseLoadClass();

  private static final Map<Character, TypeDescription> primitiveDescriptorTypes = new HashMap<>();

  private static final Map<String, TypeDescription> primitiveTypes = new HashMap<>();

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
      primitiveDescriptorTypes.put(primitiveType.getDescriptor().charAt(0), primitiveType);
      primitiveTypes.put(primitive.getName(), primitiveType);
    }
  }

  private static final boolean OUTLINING_ENABLED =
      InstrumenterConfig.get().isResolverOutliningEnabled();

  private static final boolean MEMOIZING_ENABLED =
      InstrumenterConfig.get().isResolverMemoizingEnabled();

  private static final TypeParser outlineTypeParser = new OutlineTypeParser();

  private static final TypeParser fullTypeParser = new FullTypeParser();

  private static final TypeDescription objectOutline =
      new CachingType(outlineTypeParser.parse(Object.class));

  private static final TypeInfoCache<TypeDescription> outlineTypes =
      new TypeInfoCache<>(InstrumenterConfig.get().getResolverOutlinePoolSize());

  private static final TypeInfoCache<TypeDescription> fullTypes =
      new TypeInfoCache<>(InstrumenterConfig.get().getResolverTypePoolSize());

  static final ClassNameFilter isPublicFilter =
      new ClassNameFilter(InstrumenterConfig.get().getResolverVisibilitySize());

  /** Small local cache to help deduplicate lookups when matching/transforming. */
  private final DDCache<String, LazyType> deferredTypes = DDCaches.newFixedSizeCache(16);

  private final Function<String, LazyType> deferType = LazyType::new;

  boolean installing = false;

  boolean createOutlines = OUTLINING_ENABLED;

  ClassLoader originalClassLoader;

  ClassLoader classLoader;

  ClassFileLocator classFileLocator;

  String targetName;

  byte[] targetBytecode;

  /** Sets the current class-loader context of this type-factory. */
  void switchContext(ClassLoader classLoader) {
    if (this.classLoader != classLoader || null == classFileLocator) {
      this.classLoader = classLoader;
      classFileLocator = classFileLocator(classLoader);
      // clear local type cache whenever the class-loader context changes
      deferredTypes.clear();
    }
  }

  ClassLoader currentContext() {
    return classLoader;
  }

  void beginInstall() {
    installing = true;
  }

  void endInstall() {
    installing = false;
    originalClassLoader = null;
    clearReferences();
  }

  static void clear() {
    outlineTypes.clear();
    fullTypes.clear();
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

    if (installing) {
      originalClassLoader = classLoader;
    }
  }

  /** Once matching is complete we need full descriptions for the actual transformation. */
  void enableFullDescriptions() {
    createOutlines = false;
  }

  /** Temporarily turn off full description parsing; returns {@code true} if it was enabled. */
  boolean disableFullDescriptions() {
    boolean wasEnabled = !createOutlines;
    createOutlines = OUTLINING_ENABLED;
    return wasEnabled;
  }

  /** Cleans-up local caches to minimise memory use once we're done with the type-factory. */
  void endTransform() {
    if (null == targetName) {
      return; // transformation didn't reach resolve step
    }

    if (installing) {
      // just finished transforming a support type, restore the original matching context
      switchContext(originalClassLoader);
    } else {
      clearReferences();
    }

    targetName = null;
    targetBytecode = null;
    createOutlines = OUTLINING_ENABLED;
  }

  private void clearReferences() {
    if (null != classFileLocator) {
      classLoader = null;
      classFileLocator = null;
      deferredTypes.clear();
    }
  }

  static TypeDescription findDescriptor(String descriptor) {
    TypeDescription type;
    int arity = 0;
    char c = descriptor.charAt(arity);
    while (c == '[') {
      c = descriptor.charAt(++arity);
    }
    if (c == 'L') {
      // discard leading 'L' and trailing ';'
      type = findType(descriptor.substring(arity + 1, descriptor.length() - 1).replace('/', '.'));
    } else {
      type = primitiveDescriptorTypes.get(c);
    }
    if (arity > 0 && null != type) {
      type = TypeDescription.ArrayProjection.of(type, arity);
    }
    return type;
  }

  static TypeDescription findType(String name) {
    if (name.length() < 8) { // possible primitive name
      TypeDescription type = primitiveTypes.get(name);
      if (null != type) {
        return type;
      }
    }
    return typeFactory.get().deferTypeResolution(name);
  }

  private TypeDescription deferTypeResolution(String name) {
    return deferredTypes.computeIfAbsent(name, deferType);
  }

  /** Attempts to resolve the named type using the current context. */
  TypeDescription resolveType(LazyType request) {
    if (null != classFileLocator) {
      TypeDescription result;
      if (createOutlines) {
        if ("java.lang.Object".equals(request.name)) {
          return objectOutline;
        }
        result = lookupType(request, outlineTypes, outlineTypeParser);
      } else {
        result = lookupType(request, fullTypes, fullTypeParser);
      }
      if (null != result) {
        return new CachingType(result);
      }
    }
    return null;
  }

  /** Looks up the type in the current context before falling back to parsing the class-file. */
  private TypeDescription lookupType(
      LazyType request, TypeInfoCache<TypeDescription> types, TypeParser typeParser) {
    String name = request.name;
    boolean isOutline = typeParser == outlineTypeParser;
    long fromTick = InstrumenterMetrics.tick();

    // existing type description from same classloader?
    SharedTypeInfo<TypeDescription> sharedType = types.find(name);
    if (null != sharedType
        && (name.startsWith("java.") || sharedType.sameClassLoader(classLoader))) {
      InstrumenterMetrics.reuseTypeDescription(fromTick, isOutline);
      return sharedType.get();
    }

    URL classFile = request.getClassFile();

    // existing type description from same class file?
    if (null != sharedType && sharedType.sameClassFile(classFile)) {
      InstrumenterMetrics.reuseTypeDescription(fromTick, isOutline);
      return sharedType.get();
    }

    TypeDescription type = null;

    // try to parse the original bytecode
    byte[] bytecode = request.getBytecode();
    if (null != bytecode) {
      type = typeParser.parse(bytecode);
    } else if (fallBackToLoadClass) {
      type = loadType(name, typeParser);
    }

    InstrumenterMetrics.buildTypeDescription(fromTick, isOutline);

    if (MEMOIZING_ENABLED && null != type) {
      if (type.isPublic()) {
        isPublicFilter.add(name);
      }
    }

    // share result, whether we found it or not
    types.share(name, classLoader, classFile, type);

    return type;
  }

  /** Falls back to loading the class directly; note this will bypass transformation. */
  private TypeDescription loadType(String name, TypeParser typeParser) {
    LOCATING_CLASS.begin();
    try {
      Class<?> loadedType;
      if (BOOTSTRAP_LOADER == classLoader) {
        loadedType = Class.forName(name, false, BOOTSTRAP_LOADER);
      } else if (skipLoadClass(classLoader.getClass().getName())) {
        return null; // avoid known problematic class-loaders
      } else {
        loadedType = classLoader.loadClass(name);
      }
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

  private static boolean skipLoadClass(String loaderClassName) {
    // avoid due to https://issues.apache.org/jira/browse/GROOVY-9742
    return loaderClassName.startsWith("groovy.lang.GroovyClassLoader")
        || loaderClassName.equals("org.apache.jasper.servlet.JasperLoader");
  }

  /** Type description that begins with a name and provides more details on-demand. */
  final class LazyType extends WithName implements WithLocation {
    private ClassFileLocator.Resolution location;
    private TypeDescription delegate;
    private boolean isOutline;

    LazyType(String name) {
      super(name);
    }

    @Override
    public ClassLoader getClassLoader() {
      return classLoader;
    }

    @Override
    public URL getClassFile() {
      if (null == location) {
        location = locateClassFile();
      }
      if (location instanceof ClassFileLocators.LazyResolution) {
        return ((ClassFileLocators.LazyResolution) location).url();
      }
      return UNKNOWN_CLASS_FILE;
    }

    @Override
    public byte[] getBytecode() {
      if (null == location) {
        location = locateClassFile();
      }
      if (location.isResolved()) {
        return location.resolve();
      }
      return null;
    }

    private ClassFileLocator.Resolution locateClassFile() {
      if (name.equals(targetName)) {
        return new ClassFileLocator.Resolution.Explicit(targetBytecode);
      }
      try {
        return classFileLocator.locate(name);
      } catch (Throwable ignored) {
        return new ClassFileLocator.Resolution.Illegal(name);
      }
    }

    @Override
    public int getModifiers() {
      return outline().getModifiers();
    }

    @Override
    public Generic getSuperClass() {
      return outline().getSuperClass();
    }

    @Override
    public TypeList.Generic getInterfaces() {
      return outline().getInterfaces();
    }

    @Override
    public boolean isPublic() {
      return isPublicFilter.contains(name) || super.isPublic();
    }

    private TypeDescription outline() {
      if (null != delegate) {
        return delegate; // will be at least an outline, no need to re-resolve
      }
      if (createOutlines) {
        return doResolve(true);
      }
      // temporarily switch to generating (fast) outlines as that's all we need
      createOutlines = OUTLINING_ENABLED;
      try {
        return doResolve(true);
      } finally {
        createOutlines = false;
      }
    }

    @Override
    protected TypeDescription delegate() {
      return doResolve(true);
    }

    TypeDescription doResolve(boolean throwIfMissing) {
      // re-resolve type when switching to full descriptions
      if (null == delegate || (isOutline && !createOutlines)) {
        delegate = resolveType(this);
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
