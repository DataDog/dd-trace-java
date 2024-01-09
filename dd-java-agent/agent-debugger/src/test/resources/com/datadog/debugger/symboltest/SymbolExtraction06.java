package com.datadog.debugger.symboltest;

public class SymbolExtraction06 {
  public static int main(String arg) {
    int var1 = 1;
    try {
      int var2 = 2;
      throw new RuntimeException("" + var1);
    } catch (RuntimeException rte) {
      int var3 = 3;
      System.out.println("rte = " + rte.getMessage() + var3);
    }
    return arg.length();
  }
}
