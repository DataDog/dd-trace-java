package datadog.trace.agent.tooling;

import java.util.Collection;

/**
 * Allows an {@link InstrumenterModule} to open a java module to the agent module and to the unnamed
 * module of the trigger class's class loader.
 *
 * <p>This is typically used when reflective operations need to be done and the agent cannot assume
 * that the host application has permitted them.
 */
public interface JavaModuleOpenProvider {
  /** Classes whose constructors trigger the one-time module open when first instantiated. */
  Collection<String> triggerClasses();
}
