package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public final class DDOutlinePoolStrategy implements AgentBuilder.PoolStrategy {
  public static final AgentBuilder.PoolStrategy INSTANCE = new DDOutlinePoolStrategy();

  public static void registerTypePoolFacade() {
    TypePoolFacade.registerAsSupplier();
  }

  @Override
  public TypePool typePool(ClassFileLocator ignored, ClassLoader classLoader) {
    TypePoolFacade.switchContext(classLoader);
    return TypePoolFacade.INSTANCE;
  }

  @Override
  public TypePool typePool(ClassFileLocator ignored, ClassLoader classLoader, String name) {
    TypePoolFacade.switchContext(classLoader);
    return TypePoolFacade.INSTANCE;
  }
}
