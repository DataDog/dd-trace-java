package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.factory;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.api.Config;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public final class OutlinePoolStrategy implements SharedTypePools.Supplier {
  public static final boolean enabled = Config.get().isResolverOutlinePoolEnabled();

  public static void registerAsSupplier() {
    SharedTypePools.registerIfAbsent(new OutlinePoolStrategy());
  }

  public static void beginMatching(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    if (enabled) {
      factory.get().beginMatching(classFileLocator, classLoader);
    }
  }

  public static void agentInstalled() {
    if (enabled) {
      factory.get().agentInstalled();
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

  private static final TypePool REDIRECTING_TYPE_POOL = new RedirectingTypePool();

  @Override
  public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    return REDIRECTING_TYPE_POOL;
  }

  static class RedirectingTypePool implements TypePool {
    @Override
    public Resolution describe(String name) {
      return new Resolution.Simple(name.charAt(0) == '[' ? findDescriptor(name) : findType(name));
    }

    @Override
    public void clear() {}
  }
}
