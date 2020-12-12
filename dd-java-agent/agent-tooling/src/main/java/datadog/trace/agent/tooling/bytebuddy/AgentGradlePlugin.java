package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.muzzle.MuzzleGradlePlugin;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/** Bytebuddy gradle plugin which runs various agent tooling at compile time. */
public class AgentGradlePlugin implements Plugin {
  private final Plugin[] plugins = {new MuzzleGradlePlugin(), new NewTaskForGradlePlugin()};

  @Override
  public boolean matches(TypeDescription target) {
    for (Plugin plugin : plugins) {
      if (plugin.matches(target)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public DynamicType.Builder<?> apply(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassFileLocator classFileLocator) {
    for (Plugin plugin : plugins) {
      if (plugin.matches(typeDescription)) {
        builder = plugin.apply(builder, typeDescription, classFileLocator);
      }
    }
    return builder;
  }

  @Override
  public void close() throws IOException {
    for (Plugin plugin : plugins) {
      plugin.close();
    }
  }
}
