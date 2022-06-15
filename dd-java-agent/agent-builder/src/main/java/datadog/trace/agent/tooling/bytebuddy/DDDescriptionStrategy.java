package datadog.trace.agent.tooling.bytebuddy;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

/** Strategy that only uses the type pool to describe a type for transformation. */
public final class DDDescriptionStrategy implements AgentBuilder.DescriptionStrategy {
  @Override
  public boolean isLoadedFirst() {
    return false;
  }

  @Override
  public TypeDescription apply(
      String name,
      Class<?> type,
      TypePool typePool,
      AgentBuilder.CircularityLock circularityLock,
      ClassLoader classLoader,
      JavaModule module) {
    return typePool.describe(name).resolve();
  }
}
