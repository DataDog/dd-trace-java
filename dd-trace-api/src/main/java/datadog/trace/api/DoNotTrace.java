package datadog.trace.api;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Set this annotation to a method to mute tracing until its scope is closed */
@Retention(RUNTIME)
@Target(METHOD)
public @interface DoNotTrace {}
