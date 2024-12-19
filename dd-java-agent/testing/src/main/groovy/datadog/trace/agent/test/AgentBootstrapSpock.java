package datadog.trace.agent.test;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import org.spockframework.runtime.extension.ExtensionAnnotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(RUNTIME)
@Target(TYPE)
@ExtensionAnnotation(AgentBootstrapSpockExtension.class)
public @interface AgentBootstrapSpock {
}
