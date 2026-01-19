package datadog.trace.agent.tooling.annotation;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation can be used to override the target system a specific advice can be applied on.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AppliesOn {
  /**
   * The list of target systems in addition to the one defined for the InstrumenterModule that
   * applies the advice.
   *
   * @return
   */
  InstrumenterModule.TargetSystem[] targetSystems();
}
