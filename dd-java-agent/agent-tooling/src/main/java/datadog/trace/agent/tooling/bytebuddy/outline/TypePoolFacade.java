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
