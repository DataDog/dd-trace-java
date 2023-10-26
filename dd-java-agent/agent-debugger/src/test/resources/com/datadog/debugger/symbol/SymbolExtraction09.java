package com.datadog.debugger.symbol;

import java.util.function.Supplier;

public class SymbolExtraction09 {
  public static int main(String arg) {
    int outside = 12;
    Supplier<Integer> lambda = () -> {
      int var1 = 1;
      return var1 + outside;
    };
    return lambda.get();
  }
}
