package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import java.util.Map;
import java.util.Set;

/**
 * Used to allow an {@link Instrumenter} to opt out of broad instrumentations like {@link Runnable}.
 *
 * <p>These are looked up in a separate pass before the {@link Instrumenter} is allowed to add
 * instrumentations. Note, it is up to the {@link ExcludeFilterProvider} to check if it is enabled
 * and only to
 */
public interface ExcludeFilterProvider {
  /** @return If this provider is enabled. */
  boolean isEnabled();

  /**
   * @return A mapping from {@link ExcludeType} -> {@link Set<String>} for the class names that
   *     should be excluded from broad instrumentations like {@link Runnable}
   */
  Map<ExcludeType, Set<String>> excludedClasses();
}
