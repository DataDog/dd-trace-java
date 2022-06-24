package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Custom pool strategy that provides different kinds of type descriptions:
 *
 * <ul>
 *   <li>minimally parsed type outlines for matching purposes
 *   <li>fully parsed types for the actual transformations
 * </ul>
 *
 * {@link DDOutlineTypeStrategy} is used to determine when to switch modes.
 */
public final class DDOutlinePoolStrategy implements AgentBuilder.PoolStrategy {
  public static final AgentBuilder.PoolStrategy INSTANCE = new DDOutlinePoolStrategy();

  public static void registerTypePoolFacade() {
    TypePoolFacade.registerAsSupplier();
  }

  @Override
  public TypePool typePool(ClassFileLocator ignored, ClassLoader classLoader) {
    // it's safe to ignore this ClassFileLocator because we capture the target bytecode
    // in DDOutlineTypeStrategy, so we don't need the compound locator provided here
    TypePoolFacade.switchContext(classLoader);
    return TypePoolFacade.INSTANCE;
  }

  @Override
  public TypePool typePool(ClassFileLocator ignored, ClassLoader classLoader, String name) {
    // it's safe to ignore this ClassFileLocator because we capture the target bytecode
    // in DDOutlineTypeStrategy, so we don't need the compound locator provided here
    TypePoolFacade.switchContext(classLoader);
    return TypePoolFacade.INSTANCE;
  }
}
