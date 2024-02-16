package com.datadog.debugger.symboltest;

public class SymbolExtraction04 {
  public static int main(String arg) {
    String var1 = "var1";
    for (int i = 0; i < 10; i++) {
      String var2 = "var2";
      for (int j = 0; j < 10; j++) {
        String var3 = "var3";
        for (int k = 0; k < 10; k++) {
          String var4 = "var4";
          System.out.println("var4 = " + var4);
        }
        String var5 = "var5";
        System.out.println("var5 = " + var5);
      }
    }
    return var1.length();
  }
}
