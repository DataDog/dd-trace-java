package datadog.trace.agent.tooling.bytebuddy;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.utility.JavaModule;

/** Strategy that uses {@link ClassFileLocators} to locate class files. */
public final class DDLocationStrategy implements AgentBuilder.LocationStrategy {
  public ClassFileLocator classFileLocator(ClassLoader classLoader) {
    return ClassFileLocators.classFileLocator(classLoader);
  }

  @Override
  public ClassFileLocator classFileLocator(ClassLoader classLoader, JavaModule javaModule) {
    return ClassFileLocators.classFileLocator(classLoader);
  }
}
