package datadog.trace.agent.tooling.bytebuddy;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Strategy for supplying shared type pools for matching/muzzle purposes.
 *
 * <p>Note: class transformations deliberately use a separate (local) type pool.
 */
public final class DDMatchingPoolStrategy implements AgentBuilder.PoolStrategy {
  @Override
  public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
    return SharedTypePools.typePool(classFileLocator, classLoader);
  }

  @Override
  public TypePool typePool(
      ClassFileLocator classFileLocator, ClassLoader classLoader, String name) {
    // FIXME satisfy interface constraint that currently instrumented type is not cached
    return SharedTypePools.typePool(classFileLocator, classLoader);
  }
}
