package com.datadog.debugger.symboltest;

public class SymbolExtraction08 {
  public static int main(String arg) {
    int var1 = 1;
    {
      int var2 = 2;
      int var3 = 3;
      int var4 = var2 + var3; // var4 is not in the LocalVariableTable because last statement of the scope
    }
    return arg.length();
  }
}
