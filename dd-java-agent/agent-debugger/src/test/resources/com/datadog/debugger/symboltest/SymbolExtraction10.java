package com.datadog.debugger.symboltest;

public class SymbolExtraction10 {
  public static int main(String arg) {
    Inner winner = new Inner();
    return winner.addTo(12);
  }

  static class Inner {
    private final int field1 = 1;
    public int addTo(int arg) {
      int var1 = 2;
      return var1 + arg;
    }
  }
}
