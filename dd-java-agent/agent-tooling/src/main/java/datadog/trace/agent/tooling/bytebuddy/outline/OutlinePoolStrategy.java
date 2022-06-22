package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.factory;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.api.Config;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public final class OutlinePoolStrategy implements SharedTypePools.Supplier {
  public static final boolean enabled = Config.get().isResolverOutlinePoolEnabled();

  public static void registerAsSupplier() {
    SharedTypePools.registerIfAbsent(new OutlinePoolStrategy());
  }

  private static final TypePool MATCHING_TYPE_POOL = new MatchingTypePool();

  @Override
  public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    factory.get().switchContext(classFileLocator, classLoader);
    return MATCHING_TYPE_POOL;
  }

  @Override
  public TypePool typePool(ClassLoader classLoader) {
    factory.get().switchContext(classLoader);
    return MATCHING_TYPE_POOL;
  }

  @Override
  public void cacheAnnotationForMatching(String name) {
    OutlineTypeParser.cacheAnnotationForMatching(name);
  }

  @Override
  public void beginInstall() {
    factory.get().beginInstall();
  }

  @Override
  public void endInstall() {
    factory.get().endInstall();
  }

  @Override
  public void beginTransform() {
    factory.get().beginTransform();
  }

  @Override
  public void endTransform() {
    factory.get().endTransform();
  }

  static final class MatchingTypePool implements TypePool {
    @Override
    public Resolution describe(String name) {
      TypeDescription type = name.charAt(0) == '[' ? findDescriptor(name) : findType(name);
      if (type instanceof TypeFactory.LazyType) {
        return new TypeFactory.LazyResolution((TypeFactory.LazyType) type);
      }
      return new Resolution.Simple(type);
    }

    @Override
    public void clear() {}
  }
}
