package com.datadog.debugger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class CapturedSnapshot23 {
  enum MyEnum {
    ONE("1"),
    TWO("2"),
    THREE("3");

    private final String strValue;

    MyEnum(String strValue) {
      this.strValue = strValue;
    }

    public String getStrValue() {
      return strValue;
    }
  }

  private int doit(String arg) {
    if ("".equals(arg)) {
      return Integer.parseInt(MyEnum.TWO.getStrValue());
    }
    return Integer.parseInt(convert(arg).getStrValue());
  }

  private MyEnum convert(String value) {
    switch (value) {
      case "1": return MyEnum.ONE;
      case "2": return MyEnum.TWO;
      case "3": return MyEnum.THREE;
      default:
        throw new IllegalArgumentException("Unknown value: " + value);
    }
  }

  public static int main(String arg) {
    CapturedSnapshot23 cs23 = new CapturedSnapshot23();
    return cs23.doit(arg);
  }
}
