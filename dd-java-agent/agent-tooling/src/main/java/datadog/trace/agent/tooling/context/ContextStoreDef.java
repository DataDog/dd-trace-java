package datadog.trace.agent.tooling.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Defines the context stores for an instrumenter */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContextStoreDef {

  ContextStoreMapping[] value() default {};
}
