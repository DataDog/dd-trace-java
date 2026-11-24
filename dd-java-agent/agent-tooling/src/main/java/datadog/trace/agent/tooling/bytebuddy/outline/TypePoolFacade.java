package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.typeFactory;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;

/** {@link TypePool} facade that looks up types using the active thread's {@link TypeFactory}. */
public final class TypePoolFacade implements TypePool, SharedTypePools.Supplier {
  public static final TypePoolFacade INSTANCE = new TypePoolFacade();

  public static void registerAsSupplier() {
    SharedTypePools.registerIfAbsent(INSTANCE);
    typeFactory.get().beginInstall();
  }

  @Override
  public TypePool typePool(ClassLoader classLoader) {
    switchContext(classLoader);
    return INSTANCE;
  }

  /** Switches the active thread's context to use the given class-loader. */
  public static void switchContext(ClassLoader classLoader) {
    typeFactory.get().switchContext(classLoader);
  }

  public static ClassLoader currentContext() {
    return typeFactory.get().currentContext();
  }

  @Override
  public void annotationOfInterest(String name) {
    AnnotationOutline.prepareAnnotationOutline(name);
  }

  @Override
  public void endInstall() {
    typeFactory.get().endInstall();
  }

  /** Record a new transform request for the named class-file. */
  public static void beginTransform(String name, byte[] bytecode) {
    typeFactory.get().beginTransform(name, bytecode);
  }

  /**
   * Class-file bytes of the type currently being matched, or {@code null} if unknown (e.g. when
   * re-matching already loaded classes). The array must not be modified.
   */
  public static byte[] currentTargetBytecode() {
    return typeFactory.get().targetBytecode;
  }

  /** Name of the type whose bytes {@link #currentTargetBytecode()} returns, or {@code null}. */
  public static String currentTargetName() {
    return typeFactory.get().targetName;
  }

  /**
   * Class-file bytes behind the given type's outline, if its members haven't been parsed yet;
   * otherwise null. Lets matchers sieve the members before asking for them. Must not be modified.
   */
  public static byte[] unparsedOutlineBytecode(TypeDescription type) {
    TypeDescription outline = type;
    // unwrap lazy and caching layers without triggering resolution
    for (int depth = 0; depth < 4; depth++) {
      if (outline instanceof TypeFactory.LazyType) {
        outline = ((TypeFactory.LazyType) outline).resolvedDelegate();
      } else if (outline instanceof CachingType) {
        outline = ((CachingType) outline).wrapped();
      } else {
        break;
      }
    }
    return outline instanceof TypeOutline ? ((TypeOutline) outline).unparsedBytecode() : null;
  }

  /** Switch to full descriptions, needed for the actual class transformation. */
  public static void enableFullDescriptions() {
    typeFactory.get().enableFullDescriptions();
  }

  /** Temporarily switch back to outlines, e.g. for last-minute memoization. */
  public static boolean disableFullDescriptions() {
    return typeFactory.get().disableFullDescriptions();
  }

  @Override
  public void endTransform() {
    typeFactory.get().endTransform();
  }

  @Override
  public void clear() {
    if (InstrumenterConfig.get().isResolverMemoizingEnabled()) {
      Memoizer.clear();
    }
    TypeFactory.clear();
  }

  @Override
  public Resolution describe(String name) {
    // describe elements as deferred types, lazily evaluated using the current context
    TypeDescription type = name.charAt(0) == '[' ? findDescriptor(name) : findType(name);
    if (type instanceof TypeFactory.LazyType) {
      return new TypeFactory.LazyResolution((TypeFactory.LazyType) type);
    }
    return new Resolution.Simple(type);
  }
}
