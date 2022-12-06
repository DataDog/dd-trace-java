package com.datadog.debugger;

public class CapturedSnapshot10 {
  public static int main(String arg) {
    int var1 = 1;
    if (Integer.parseInt(arg) == 1) {
      TopLevel01 topLevel01 = new TopLevel01();
      return topLevel01.process(42);
    }
    if (Integer.parseInt(arg) == 2) {
      var1 = 2;
      return var1;
    }
    var1 = 3;
    return var1;
  }
}

class TopLevel01 {
  public int process(int arg) {
    return arg * arg;
  }
}
