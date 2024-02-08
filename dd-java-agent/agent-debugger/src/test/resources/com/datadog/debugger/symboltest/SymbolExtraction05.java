package com.datadog.debugger.symboltest;

public class SymbolExtraction05 {
  public static int main(String arg) {
    int i = 0;
    while (i < 10) {
      int var1 = 10;
      int j = 0;
      while (j < 10) {
        int var2 = 1;
        j++;
      }
      i++;
    }
    return arg.length();
  }
}
