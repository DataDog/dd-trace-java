package com.datadog.debugger.symboltest;

import java.util.function.Supplier;

public class SymbolExtraction09 {
  static int staticIntField = 42;
  public static int main(String arg) {
    int outside = 12;
    int outside2 = 1337;
    Supplier<Integer> lambda = () -> {
      int var1 = 1;
      return var1 + outside + staticIntField;
    };
    return lambda.get();
  }

  int intField = 42;
  public int process() {
    Supplier<Integer> supplier = () -> {
      int var1 = 1;
      return var1 + intField;
    };
    return supplier.get();
  }
}
