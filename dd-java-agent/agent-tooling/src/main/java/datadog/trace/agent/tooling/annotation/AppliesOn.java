package datadog.trace.agent.tooling.annotation;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies which {@link InstrumenterModule.TargetSystem}s an advice class should be applied for,
 * overriding the default target system inherited from the parent {@link InstrumenterModule}.
 *
 * <p>By default, advice classes are applied when their parent {@code InstrumenterModule}'s target
 * system is enabled. This annotation allows fine-grained control over which target systems an
 * individual advice class should run for, independent of the module's target system.
 *
 * @see InstrumenterModule.TargetSystem
 * @see InstrumenterModule
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AppliesOn {
  /**
   * The target systems for which this advice is applied.
   *
   * <p>The advice will only be applied if at least one of the specified target systems is enabled.
   * If multiple target systems are specified, the advice applies when any of them are enabled (OR
   * logic).
   *
   * @return the target systems this advice applies to
   */
  InstrumenterModule.TargetSystem[] value();
}
