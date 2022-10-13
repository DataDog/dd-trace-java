package datadog.trace.api.iast;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InvokeDynamicHelper {
  /* A static method on the same class taking 0 arguments and returning a MethodHandle */
  String fallbackMethodHandleProvider() default "";
}
