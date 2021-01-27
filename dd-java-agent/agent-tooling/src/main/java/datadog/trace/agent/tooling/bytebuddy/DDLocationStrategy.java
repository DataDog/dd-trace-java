package datadog.trace.agent.tooling.bytebuddy;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.utility.JavaModule;

/** Strategy that uses {@link DDClassFileLocator} to locate class files. */
public final class DDLocationStrategy implements AgentBuilder.LocationStrategy {
  public ClassFileLocator classFileLocator(final ClassLoader classLoader) {
    return classFileLocator(classLoader, null);
  }

  @Override
  public ClassFileLocator classFileLocator(
      final ClassLoader classLoader, final JavaModule javaModule) {
    return new DDClassFileLocator(classLoader);
  }
}
