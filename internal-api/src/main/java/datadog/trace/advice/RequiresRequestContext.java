package datadog.trace.advice;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Transforms the enter and exit advice so that the existence of an active {@link RequestContext} is
 * checked before the body of the advice is run.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface RequiresRequestContext {
  RequestContextSlot value();
}
