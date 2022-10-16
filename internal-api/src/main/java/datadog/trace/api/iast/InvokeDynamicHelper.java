package datadog.trace.api.iast;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InvokeDynamicHelper {
  // in the form java/lang/String.format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;
  String fallbackMethod() default "";
}
