package com.datadog.debugger.symboltest;

public class SymbolExtraction11 {
  private final int field1 = 1;
  public static int main(int arg) {
    int var1 = 1;
    if (arg == 42) {
      int var2 = 2;
      return var2;
    }
    return var1 + arg;
  }
}
