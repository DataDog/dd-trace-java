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

  public static void registerAnnotationForMatching(String name) {
    if (enabled) {
      OutlineTypeParser.registerAnnotationForMatching(name);
    }
  }

  public static void switchContext(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    if (enabled) {
      factory.get().switchContext(classFileLocator, classLoader);
    }
  }

  public static void beginInstall() {
    if (enabled) {
      factory.get().beginInstall();
    }
  }

  public static void endInstall() {
    if (enabled) {
      factory.get().endInstall();
    }
  }

  public static void beginTransform() {
    if (enabled) {
      factory.get().beginTransform();
    }
  }

  public static void endTransform() {
    if (enabled) {
      factory.get().endTransform();
    }
  }

  private static final TypePool MATCHING_TYPE_POOL = new MatchingTypePool();

  @Override
  public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    return MATCHING_TYPE_POOL;
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
