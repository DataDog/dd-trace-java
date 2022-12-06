package com.datadog.debugger;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class CapturedSnapshot15 extends Base {
  private static final long LONG_CST = 4_000_000_000L;
  static long[] globalArray;
  private final long value;

  public CapturedSnapshot15() {
    super(LONG_CST,
        Singleton.INSTANCE.PI + Singleton.INSTANCE.doublePI,
        globalArray = new long[0],
        new double[0][0],
        Singleton.INSTANCE.s1 != null ? Singleton.INSTANCE.s1 : Singleton.INSTANCE.s2,
        String::valueOf);
    this.value = LONG_CST + 1;
  }

  public CapturedSnapshot15(String s1, long l1, String s3) {
    this(s1, (s) -> String.valueOf(l1), s3);
  }

  public CapturedSnapshot15(String s1, Function<String, String> f1, String s3) {
    super();
    this.value = LONG_CST;
  }

  public static long main(String arg) {
    new CapturedSnapshot15("", 42, "");
    return new CapturedSnapshot15().value;
  }
}

class Base {
  Base() {

  }
  Base(long l, double d, long[] a1, double[][] a2, String a3, Function<String, String> f1) {

  }
}

class Singleton {
  static Singleton INSTANCE = new Singleton(3.14);
  final double PI;
  final double doublePI;
  final String s1 = "foo";
  final String s2 = "bar";
  Singleton(double d) {
    this.PI = d;
    this.doublePI = d * 2;
  }
}
