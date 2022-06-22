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

final class TypeFactory {

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

  private final DDCache<String, LazyType> deferredTypes = DDCaches.newFixedSizeCache(16);

  private final Function<String, LazyType> deferType =
      new Function<String, LazyType>() {
        @Override
        public LazyType apply(String input) {
          return new LazyType(input);
        }
      };

  private static final ThreadLocal<MatchingContext> originalContext = new ThreadLocal<>();

  private boolean installing = false;

  private boolean createOutlines = true;

  private ClassFileLocator classFileLocator;

  private ClassLoader classLoader;

  void switchContext(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    if (this.classFileLocator != classFileLocator) {
      if (installing
          && this.classLoader != classLoader
          && classFileLocator instanceof ClassFileLocator.Compound) {
        originalContext.set(new MatchingContext(this.classFileLocator, this.classLoader));
      }
      this.classFileLocator = classFileLocator;
      this.classLoader = classLoader;
    }
  }

  void switchContext(ClassLoader classLoader) {
    if (this.classLoader != classLoader) {
      this.classFileLocator = classFileLocator(classLoader);
      this.classLoader = classLoader;
    }
  }

  void beginInstall() {
    installing = true;
  }

  void endInstall() {
    installing = false;
    originalContext.remove();
    clearReferences();
  }

  void beginTransform() {
    createOutlines = false;
  }

  void endTransform() {
    createOutlines = true;
    if (installing) {
      MatchingContext context = originalContext.get();
      if (null != context) {
        classFileLocator = context.classFileLocator;
        classLoader = context.classLoader;
        originalContext.remove();
      }
    } else {
      clearReferences();
    }
  }

  private void clearReferences() {
    deferredTypes.clear();
    classFileLocator = null;
    classLoader = null;
  }

  private TypeDescription deferTypeResolution(String name) {
    return deferredTypes.computeIfAbsent(name, deferType);
  }

  private TypeDescription resolveType(String name) {
    if (null == classFileLocator) {
      return TypeDescription.UNDEFINED;
    }

    TypeDescription type =
        createOutlines
            ? lookupType(name, outlineTypes, outlineTypeParser)
            : lookupType(name, fullTypes, fullTypeParser);

    return null != type ? new CachingType(type) : null;
  }

  private TypeDescription lookupType(
      String name, TypeInfoCache<TypeDescription> types, TypeParser typeParser) {

    // existing info from same classloader?
    SharedTypeInfo<TypeDescription> typeInfo = types.find(name);
    if (null != typeInfo && typeInfo.sameClassLoader(classLoader)) {
      return typeInfo.resolve();
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
      return typeInfo.resolve();
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
      return typeParser.parse(loadedType);
    } catch (Throwable ignored) {
      return null;
    } finally {
      LOCATING_CLASS.end();
    }
  }

  static final class MatchingContext {
    final ClassFileLocator classFileLocator;

    final ClassLoader classLoader;

    MatchingContext(ClassFileLocator classFileLocator, ClassLoader classLoader) {
      this.classFileLocator = classFileLocator;
      this.classLoader = classLoader;
    }
  }

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
