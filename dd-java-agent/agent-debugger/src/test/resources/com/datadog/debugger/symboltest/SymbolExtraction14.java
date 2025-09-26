package com.datadog.debugger.symboltest;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public abstract class SymbolExtraction14 extends Object implements I1, I2{

  public static int main(String arg) {
    System.out.println(MyEnum.ONE);
    return 42;
  }

  protected abstract void m1();
  private strictfp synchronized final String m2(String... strVarArgs) {
    return null;
  }

}

interface I1 {
  default void m3(){}
  static String m4(String arg){
    return arg;
  }
}

interface I2 {

}

enum MyEnum {
  ONE,
  TWO,
  THREE
}
