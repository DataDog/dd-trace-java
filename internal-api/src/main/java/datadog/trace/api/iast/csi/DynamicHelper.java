package datadog.trace.api.iast.csi;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(RUNTIME)
@Target(METHOD)
public @interface DynamicHelper {

  Class<?> owner();

  String method();

  Class<?> returnType();

  Class<?>[] argumentTypes();
}
