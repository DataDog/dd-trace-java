package com.datadog.debugger;

public class CapturedSnapshot14 {
  private int value;
  private CapturedSnapshot14 parent;
  private CapturedSnapshot14 left;
  private CapturedSnapshot14 right;

  CapturedSnapshot14(int value) {
    this.value = value; parent = null; left = null; right = null;
  }

  CapturedSnapshot14(int value, CapturedSnapshot14 parent) {
    this(value);
    parent = parent; left = null; right = null;
  }

  CapturedSnapshot14(int value, int left, int right) {
    this.value = value;
    this.parent = null;
    this.left = new CapturedSnapshot14(left, this);
    this.right = new CapturedSnapshot14(right, this);
  }

  public static int main(String arg) {
    new CapturedSnapshot14(0, 1, 2);
    return 42;
  }
}
