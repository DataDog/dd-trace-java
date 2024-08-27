package com.datadog.debugger;

public class CapturedSnapshot19 {
  private static String strField = "foo";
  private static int intField = 1001;
  private static double doubleField = Math.PI;
  private static int[] intArrayField = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

  public static int main(String arg) {
    if ("inherited".equals(arg)) {
      return new Inherited().f();
    }
    return new CapturedSnapshot19().process(arg);
  }

  int process(String arg) {
    return 42;
  }

  static class Base {
    private static int intValue = 24;
    protected static double doubleValue = 3.14;
    private static Object obj1;
    private static long[] longValues = new long[] {1, 2, 3, 4};
    private static String[] strValues = new String[] {"foo", "bar"};

    public Base(Object obj1) {
      this.obj1 = obj1;
    }

    public int f() {
      intValue *= 2;
      return 42;
    }
  }

  static class Inherited extends Base {
    private static final Object OBJ = new Object();
    private static String strValue = "foobar";
    private static Object obj2;

    public Inherited() {
      super(new Base(OBJ));
      this.obj2 = new Base(new Object());
    }

    public int f() {
      strValue = "barfoo";
      doubleValue *= 2;
      return super.f();
    }
  }
}
