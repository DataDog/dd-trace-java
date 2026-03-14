package com.datadog.debugger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@MyTypeAnnotation
public record CapturedSnapshot33(@MyTypeUseAnnotation String strField) {
  public static CapturedSnapshot33 parse(String arg) {
    return new CapturedSnapshot33(arg);
  }
}


@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@interface MyTypeAnnotation {
}

@Target({ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface MyTypeUseAnnotation {
}
