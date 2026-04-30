package datadog.trace.agent.tooling;

import java.util.Collection;

/**
 * Allows an {@link InstrumenterModule} to possibly open a java module to the unnamed module.
 *
 * <p>This is typically used when reflective operations need to be done and the agent cannot assume
 * that the host application has permitted them.
 */
public interface JavaModuleOpenProvider {
  /** Classes whose constructors trigger the one-time module open when first instantiated. */
  Collection<String> triggerClasses();
}
