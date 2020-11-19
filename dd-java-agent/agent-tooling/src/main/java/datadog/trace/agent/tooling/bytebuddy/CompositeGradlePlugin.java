package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.advice.transformation.NewTaskForAdvicePlugin;
import datadog.trace.agent.tooling.muzzle.MuzzleGradlePlugin;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

public class CompositeGradlePlugin implements Plugin {
  private final Plugin[] delegates =
      new Plugin[] {new MuzzleGradlePlugin(), new NewTaskForAdvicePlugin()};

  @Override
  public DynamicType.Builder<?> apply(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassFileLocator classFileLocator) {
    for (Plugin delegate : delegates) {
      if (delegate.matches(typeDescription)) {
        builder = delegate.apply(builder, typeDescription, classFileLocator);
      }
    }
    return builder;
  }

  @Override
  public void close() throws IOException {
    for (Plugin delegate : delegates) {
      delegate.close();
    }
  }

  @Override
  public boolean matches(TypeDescription target) {
    boolean matches = false;
    for (Plugin delegate : delegates) {
      matches |= delegate.matches(target);
    }
    return matches;
  }
}
