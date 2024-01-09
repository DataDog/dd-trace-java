package com.datadog.debugger.symboltest;
public class SymbolExtraction01 {
  public static int main(String arg) {
    int var1 = 1;
    if (Integer.parseInt(arg) == 2) {
      int var2 = 2;
      for (int i = 0; i <= 9; i++) {
        int foo = 13;
        int bar = 13;
        System.out.println(i + foo + bar);
        int j = 0;
        while (j < 10) {
          int var4 = 1;
          j++;
        }
      }
      return var2;
    }
    int var3 = 3;
    return var1 + var3;
  }
}
