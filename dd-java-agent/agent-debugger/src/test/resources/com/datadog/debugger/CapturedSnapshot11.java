package com.datadog.debugger;

public class CapturedSnapshot11 {
  public static int main(String arg) {
    int var1 = 1;
    if (Integer.parseInt(arg) == 1) {
      return var1;
    }
    if (Integer.parseInt(arg) == 2) {
      var1 = 2;
      return var1;
    }
    var1 = 3;
    return var1;
  }
}
