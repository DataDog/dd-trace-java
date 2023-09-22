package com.datadog.debugger.symbol;

public class SymbolExtraction08 {
  public static int main(String arg) {
    int var1 = 1;
    {
      int var2 = 2;
      int var3 = 3;
      int var4 = var2 + var3; // Wow, why is this not in the LocalVariableTable?
    }
    return arg.length();
  }
}
