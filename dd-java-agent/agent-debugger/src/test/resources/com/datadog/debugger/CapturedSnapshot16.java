package com.datadog.debugger;

public class CapturedSnapshot16 {

  public int overload() {
    return 42;
  }

  public int overload(String s, Object[] args) {
    return Integer.parseInt(s);
  }

  public int overload(String s, int i1, int i2, int i3) {
    return Integer.parseInt(s) + i1 + i2 + i3;
  }

  public int overload(String s, double d) {
    return Integer.parseInt(s) + (int)d;
  }

  public static int main(String arg) {
    CapturedSnapshot16 cs16 = new CapturedSnapshot16();
    int sum = 0;
    sum += cs16.overload();
    sum += cs16.overload("1", new Object[] { 1, 2 });
    sum += cs16.overload("2", 3, 4, 5);
    sum += cs16.overload("3", 3.14);
    return sum;
  }
}
