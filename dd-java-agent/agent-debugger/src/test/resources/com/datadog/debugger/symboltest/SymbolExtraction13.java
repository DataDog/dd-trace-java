package com.datadog.debugger.symboltest;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@MyAnnotation("class")
@MyMarker
public class SymbolExtraction13 {

  @MyAnnotation("field")
  private int intField;
  public static  volatile String strField;
  protected final transient double doubleField = 3.14;

  @MyAnnotation("method")
  public static int main(String arg) {
    System.out.println(MyAnnotation.class);
    return 42;
  }

  private static class InnerClass {

  }
}

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@interface MyAnnotation {
  String value() default "";
}

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@interface MyMarker {
}
