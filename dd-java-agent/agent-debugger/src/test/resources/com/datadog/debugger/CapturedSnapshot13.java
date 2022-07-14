package com.datadog.debugger;

import java.util.List;
import java.util.ArrayList;

public class CapturedSnapshot13 extends BaseClass {
  private final List<CapturedSnapshot13> children = new ArrayList<>();

  CapturedSnapshot13(int arg) {
    super(arg);
    if (arg % 2 == 0) {
      this.children.add(new CapturedSnapshot13(arg, this));
    }
  }

  CapturedSnapshot13(int arg, CapturedSnapshot13 parent) {
    super(arg);
  }

  public static int main(String arg) {
    new CapturedSnapshot13(0);
    return 42;
  }

}

class BaseClass {
  BaseClass(int arg) {

  }
}
