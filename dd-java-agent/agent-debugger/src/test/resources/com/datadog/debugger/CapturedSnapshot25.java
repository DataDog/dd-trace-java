package com.datadog.debugger;

public class CapturedSnapshot25 {

  private int intField;
  private long longField;
  private float floatField;
  private double doubleField;
  private boolean booleanField;
  private byte byteField;
  private short shortField;
  private char charField;

  private int intFunction(int arg) {
    return arg;
  }

  private long longFunction(long arg) {
    return arg;
  }

  private float floatFunction(float arg) {
    return arg;
  }

  private double doubleFunction(double arg) {
    return arg;
  }

  private boolean booleanFunction(int arg) {
    return true;
  }

  private byte byteFunction(int arg) {
    return (byte)arg;
  }

  private short shortFunction(int arg) {
    return (short)arg;
  }

  private char charFunction(int arg) {
    return (char)arg;
  }

  public static int main(String arg) {
    CapturedSnapshot25 cs25 = new CapturedSnapshot25();
    switch (arg) {
      case "int":
        cs25.intField = cs25.intFunction(42);
        break;
      case "long":
        cs25.longField = cs25.longFunction(1001);
        break;
      case "float":
        cs25.floatField = cs25.floatFunction(3.14f);
        break;
      case "double":
        cs25.doubleField = cs25.doubleFunction(Math.E);
        break;
      case "boolean":
        cs25.booleanField = cs25.booleanFunction(1);
        break;
      case "byte":
        cs25.byteField = cs25.byteFunction(0x42);
        break;
      case "short":
        cs25.shortField = cs25.shortFunction(1001);
        break;
      case "char":
        cs25.charField = cs25.charFunction(31);
        break;
    }
    return 42;
  }
}
